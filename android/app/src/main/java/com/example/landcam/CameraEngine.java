package com.example.landcam;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiNetworkSpecifier;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;


/**
 * LANDCAM camera engine.
 *
 * Owns camera networking, discovery orchestration, Camera API calls,
 * live-view frame parsing, spectral processing, autofocus configuration,
 * capture, storage and native events.
 */
public class CameraEngine {



    private static final String TAG = "LandCamMonitor";

    private static final String METHOD_CHANNEL =
            "landcam/native";

    private static final String EVENT_CHANNEL =
            "landcam/events";

    private static final String SONY_SCALAR_ST =
            "urn:schemas-sony-com:service:ScalarWebAPI:1";

    private static final String SSDP_ADDRESS =
            "239.255.255.250";

    private static final int SSDP_PORT =
            1900;

    private static final int LOCATION_PERMISSION_REQUEST =
            100;

    private static final int TCP_PROBE_TIMEOUT_MS =
            700;

    private static final int HTTP_PROBE_TIMEOUT_MS =
            2200;

    private static final int API_CONNECT_TIMEOUT_MS =
            8000;

    private static final int API_READ_TIMEOUT_MS =
            12000;

    private static final int LIVEVIEW_CONNECT_TIMEOUT_MS =
            8000;

    private static final int DISCOVERY_THREADS =
            24;

    private static final int DISCOVERY_MAX_HOSTS =
            254;

    private static final long DISCOVERY_TOTAL_TIMEOUT_MS =
            15000L;

    private static final long SSDP_TIMEOUT_MS =
            3500L;

    private static final long FRAME_EVENT_INTERVAL_MS =
            70L;

    /**
     * NDVI metadata/CPU-preview events are intentionally slower than the
     * internal GPU render cadence. GPU output itself is kept on the surface.
     * camera stream.  A bounded latest-frame worker keeps the socket reader
     * responsive even when bitmap analysis briefly takes longer.
     */
    private static final long NDVI_PREVIEW_INTERVAL_MS =
            40L;

    private static final int NDVI_HISTOGRAM_LOW_PERCENT = 2;
    private static final int NDVI_HISTOGRAM_HIGH_PERCENT = 98;
    private static final int NDVI_MIN_VALID_SIGNAL = 6;
    private static final int NDVI_MAX_PREVIEW_PIXELS = 100000;
    private static final int NDVI_LUT_SIZE = 256;
    private static final int[] NDVI_COLOR_LUT =
            buildNdviColorLut();

    /**
     * LANDCAM dual-optical processing:
     *
     *   LEFT FOV  -> RGB / R / G / B
     *   RIGHT FOV -> NIR
     *
     * ROI detection is performed once per source resolution and the resulting
     * rectangles are reused for every subsequent frame at that resolution.
     * This avoids per-frame circle detection and keeps live-view stable.
     */
    private static final int ROI_SCAN_STEP = 4;
    private static final float ROI_PROFILE_COVERAGE = 0.10f;
    private static final int ROI_BLACK_LUMA_THRESHOLD = 16;
    private static final int ROI_PROFILE_SMOOTH_RADIUS = 8;
    private static final float ROI_SQUARE_SAFETY_FACTOR = 0.95f;
    private static final int IMAGE_JPEG_QUALITY = 94;
    private static final int MAX_PROCESSING_DIMENSION = 4096;

    // Conservative fallbacks for the known dual-circle camera geometry.
    // They are used only when automatic circle-bound estimation fails.
    private static final float FALLBACK_RGB_LEFT = 0.00f;
    private static final float FALLBACK_RGB_TOP = 0.085f;
    private static final float FALLBACK_RGB_RIGHT = 0.455f;
    private static final float FALLBACK_RGB_BOTTOM = 0.955f;
    private static final float FALLBACK_NIR_LEFT = 0.505f;
    private static final float FALLBACK_NIR_TOP = 0.11f;
    private static final float FALLBACK_NIR_RIGHT = 1.00f;
    private static final float FALLBACK_NIR_BOTTOM = 0.93f;

    /*
     * Unified RGB/NIR registration.
     *
     * The RGB optical crop is the master image plane.  The NIR crop is mapped
     * into that same coordinate system before NIR display or NDVI analysis.
     * These defaults intentionally describe a centered registration because
     * the current source does not contain a factory stereo calibration file.
     *
     * The four values below are calibration hooks, not arbitrary per-frame
     * corrections.  Once the real two-lens rig is calibrated, these constants
     * can hold the measured residual NIR scale/rotation/translation.
     */
    private static final float NIR_REGISTRATION_SCALE_X = 1.0f;
    private static final float NIR_REGISTRATION_SCALE_Y = 1.0f;
    private static final float NIR_REGISTRATION_ROTATION_DEG = 0.0f;
    private static final float NIR_REGISTRATION_COS =
            (float) Math.cos(
                    Math.toRadians(
                            NIR_REGISTRATION_ROTATION_DEG
                    )
            );
    private static final float NIR_REGISTRATION_SIN =
            (float) Math.sin(
                    Math.toRadians(
                            NIR_REGISTRATION_ROTATION_DEG
                    )
            );
    private static final float NIR_REGISTRATION_SHIFT_X_PX = 0.0f;
    private static final float NIR_REGISTRATION_SHIFT_Y_PX = 0.0f;

    private static final float NDVI_NIR_GAIN_MIN = 0.25f;
    private static final float NDVI_NIR_GAIN_MAX = 4.0f;
    private static final float NDVI_GAIN_SMOOTHING = 0.20f;
    private static final int NDVI_BALANCE_PERCENTILE = 85;
    private static final int NDVI_PREVIEW_JPEG_QUALITY = 86;
    private static final int NDVI_CAPTURE_JPEG_QUALITY = 92;

    /**
     * GPU NDVI path. Live NDVI is rendered directly into a Flutter
     * SurfaceTexture. No glReadPixels() and no JPEG encode are performed on
     * the live NDVI path. This is the key performance optimization.
     */
    private static final int GPU_NDVI_OUTPUT_WIDTH = 960;
    private static final int GPU_NDVI_OUTPUT_HEIGHT = 960;
    private static final long GPU_NDVI_CALIBRATION_INTERVAL_MS = 350L;
    private static final int GPU_NDVI_GAIN_SAMPLE_GRID = 32;
    private static final float GPU_NDVI_MIN_SIGNAL = 6.0f / 255.0f;

    // NIR JPEGs from the camera are rendered as grayscale using luma.
    // This preserves the camera's intensity differences without inventing a
    // false colour map.
    private static final boolean NIR_USE_LUMA = true;

    /*
     * UI-facing protocol label.
     *
     * This is intentionally generic so the Flutter UI never displays
     * the camera vendor/brand.
     */
    private static final String UI_PROTOCOL_LABEL =
            "REMOTE CAMERA API";

    private static final List<Integer> SONY_API_PORTS =
            Arrays.asList(
                    8080,
                    10000,
                    80,
                    61000
            );

    private static final List<Integer> SONY_DESCRIPTION_PORTS =
            Arrays.asList(
                    64321,
                    61000,
                    8080,
                    10000,
                    80
            );

    private static final List<String> DESCRIPTION_PATHS =
            Arrays.asList(
                    "/sony/ssdp/dd.xml",
                    "/scalarwebapi_dd.xml",
                    "/dd.xml"
            );

    private static final Pattern SONY_SSID_PATTERN =
            Pattern.compile(
                    "DIRECT-[A-Za-z0-9_-]+:[A-Za-z0-9_.-]{1,48}"
            );

    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile(
                    "(?:password|pass|pwd)\\s*[:=]\\s*([A-Za-z0-9]{8,63})",
                    Pattern.CASE_INSENSITIVE
            );

    private static final Pattern EIGHT_DIGIT_PATTERN =
            Pattern.compile(
                    "(?<!\\d)\\d{8}(?!\\d)"
            );

    private static final Pattern SSDP_LOCATION_PATTERN =
            Pattern.compile(
                    "(?im)^location\\s*:\\s*(\\S+)\\s*$"
            );

    private static final Pattern XML_ACTION_URL_PATTERN =
            Pattern.compile(
                    "<[^>]*X_ScalarWebAPI_ActionList_URL[^>]*>(.*?)</[^>]*X_ScalarWebAPI_ActionList_URL>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );

    private static final Pattern XML_BASE_URL_PATTERN =
            Pattern.compile(
                    "<[^>]*X_ScalarWebAPI_BaseURL[^>]*>(.*?)</[^>]*X_ScalarWebAPI_BaseURL>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );

    private static final Pattern XML_SERVICE_TYPE_PATTERN =
            Pattern.compile(
                    "<[^>]*X_ScalarWebAPI_ServiceType[^>]*>(.*?)</[^>]*X_ScalarWebAPI_ServiceType>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );

    private static final Pattern XML_FRIENDLY_NAME_PATTERN =
            Pattern.compile(
                    "<[^>]*friendlyName[^>]*>(.*?)</[^>]*friendlyName>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );

    private static final Pattern XML_MODEL_NAME_PATTERN =
            Pattern.compile(
                    "<[^>]*modelName[^>]*>(.*?)</[^>]*modelName>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );

    private static final List<String> SOFTWARE_DISPLAY_BANDS =
            Arrays.asList(
                    "RGB",
                    "R",
                    "G",
                    "B"
            );

    /*
     * NIR is not part of the standard Sony RGB live-view stream.
     * LANDCAM therefore treats NIR as a real hardware/API capability only
     * when the connected camera exposes a dedicated NIR/infrared API.
     *
     * The aliases below make the bridge tolerant of camera-specific naming
     * conventions while keeping the UI honest: NIR is advertised only after
     * a matching API method is actually discovered.
     */
    private static final List<String> NIR_METHOD_CANDIDATES =
            Arrays.asList(
                    "startNIRLiveview",
                    "startNirLiveview",
                    "startNIRLiveView",
                    "startNirLiveView",
                    "startInfraredLiveview",
                    "startInfraredLiveView",
                    "getNIRLiveview",
                    "getNirLiveview",
                    "getNIRFrame",
                    "getNirFrame",
                    "getInfraredFrame",
                    "getNIRImage",
                    "getNirImage",
                    "getInfraredImage",
                    "actTakeNIRPicture",
                    "actTakeNirPicture",
                    "actTakeInfraredPicture"
            );


    private ConnectivityManager connectivityManager;

    private ConnectivityManager.NetworkCallback networkCallback;

    private volatile Network currentNetwork;

    private volatile HttpURLConnection liveviewConnection;

    private volatile WifiManager.MulticastLock multicastLock;

    private final ExecutorService executor =
            Executors.newFixedThreadPool(6);

    private final ExecutorService discoveryExecutor =
            Executors.newFixedThreadPool(
                    DISCOVERY_THREADS
            );

    private final AtomicBoolean isStreaming =
            new AtomicBoolean(false);

    private final AtomicBoolean isNirPolling =
            new AtomicBoolean(false);

    private final AtomicBoolean isEngineStarting =
            new AtomicBoolean(false);

    private final AtomicBoolean isDiscoveryRunning =
            new AtomicBoolean(false);

    private final AtomicLong sessionGeneration =
            new AtomicLong(0L);

    private final AtomicReference<CameraEndpoint> discoveredEndpoint =
            new AtomicReference<>(null);

    private volatile byte[] lastFrameBytes;
    private volatile byte[] lastCompositeFrameBytes;

    private volatile int calibratedSourceWidth = -1;
    private volatile int calibratedSourceHeight = -1;
    private volatile Rect rgbCropRect;
    private volatile Rect nirCropRect;
    private volatile long lastQuadEventAt = 0L;
    private final AtomicLong spectralGeneration = new AtomicLong(0L);

    private volatile boolean isQuadMode =
            false;

    private volatile boolean grayscaleMode =
            false;

    private volatile boolean firstFrameSent =
            false;

    /*
     * Preview ownership.
     *
     * Every user-visible preview commit belongs to exactly one generation.
     * A mode change invalidates all work from the previous generation.
     * This prevents a stale RGB/spectral frame from arriving after NDVI
     * has already become the active mode.
     */
    private final AtomicLong previewGeneration =
            new AtomicLong(0L);

    private volatile long lastNormalFrameEventAt =
            0L;

    private volatile long lastNdviFrameEventAt =
            0L;

    private static final int NDVI_RENDERER_UNKNOWN = 0;
    private static final int NDVI_RENDERER_GPU = 1;
    private static final int NDVI_RENDERER_CPU = 2;

    /*
     * Renderer policy is sticky for the lifetime of one NDVI session.
     * GPU failures do not make the renderer oscillate GPU -> CPU -> GPU.
     * A surface that is temporarily unavailable does NOT force CPU mode.
     */
    private volatile int ndviRendererMode =
            NDVI_RENDERER_UNKNOWN;

    private volatile String lastSsid;

    private volatile String lastPassword;

    private volatile String cameraHost;

    private volatile int cameraPort =
            -1;

    private volatile String cameraScheme =
            "http";

    private volatile String cameraApiUrl;

    private volatile String lastLiveviewUrl;

    private volatile String activeSpectralBand =
            "RGB";

    private volatile boolean realNirAvailable =
            false;

    private volatile String nirApiMethod;

    private volatile String nirStreamUrl;

    private volatile String streamingBand =
            "RGB";

    /*
     * These values are still useful internally while talking to the camera,
     * but are NEVER exposed through UI event payloads.
     */
    private volatile String cameraBrand =
            "UNKNOWN";

    private volatile String cameraModel =
            "UNKNOWN";

    /*
     * Generic UI label only.
     *
     * Never assign a vendor-specific name here.
     */
    private volatile String cameraProtocol =
            UI_PROTOCOL_LABEL;

    private volatile String cameraFriendlyName =
            "";



    public interface Listener {
        void onNativeEvent(String type, Object data);

        void onEngineError(String message, Throwable error);
    }

    private final Context context;
    private final Listener listener;

    private volatile boolean initialized = false;
    private volatile boolean nfcListening = false;

    private static final String CAPTURE_MODE_PROCESSED = "PROCESSED";
    private static final String CAPTURE_MODE_RAW = "RAW";
    private static final String CAPTURE_MODE_BOTH = "RAW + PROCESSED";

    private static final String PERFORMANCE_PERFORMANCE = "PERFORMANCE";
    private static final String PERFORMANCE_BALANCED = "BALANCED";
    private static final String PERFORMANCE_HIGH_QUALITY = "HIGH QUALITY";

    private volatile String captureMode = CAPTURE_MODE_PROCESSED;
    private volatile String performanceMode = PERFORMANCE_BALANCED;

    /*
     * Advanced Settings are native-owned configuration. CameraEngine is the
     * single source of truth for capture output and performance workload.
     */
    private volatile double previewScale = 0.75d;
    private volatile double processingScale = 0.75d;
    private volatile int processingEveryNFrames = 1;

    /*
     * Counts normal (non-NDVI) preview processing jobs. NDVI has its own
     * renderer cadence and is intentionally excluded.
     */
    private final AtomicLong previewProcessingSequence =
            new AtomicLong(0L);

    private static final long NDVI_EVENT_INTERVAL_MS = 150L;
    private volatile boolean ndviEnabled = false;
    private volatile long lastNdviEventAt = 0L;
    private volatile long lastNdviPreviewAt = 0L;
    private volatile long lastNdviComputedAt = 0L;
    private volatile double lastNdviValue = Double.NaN;
    private volatile int lastNdviValidPixels = 0;
    private volatile float lastNdviNirGain = 1.0f;
    private volatile long lastNdviCalibrationAt = 0L;

    private final Object gpuNdviLock = new Object();
    private volatile SurfaceTexture gpuNdviSurfaceTexture;
    private volatile Surface gpuNdviSurface;
    private volatile GpuNdviRenderer gpuNdviRenderer;

    /**
     * Latest-frame-only queue for bitmap work.  The network/live-view reader
     * never waits for NDVI or JPEG processing; stale frames are replaced.
     */
    private final AtomicReference<byte[]> pendingCompositeFrame =
            new AtomicReference<>();
    private final AtomicBoolean frameProcessorRunning =
            new AtomicBoolean(false);

    public CameraEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.connectivityManager = (ConnectivityManager) this.context.getSystemService(
                Context.CONNECTIVITY_SERVICE
        );
    }

    /**
     * Attaches the Flutter GPU output surface used by live NDVI. The
     * SurfaceTexture itself is owned by Flutter's TextureRegistry; CameraEngine
     * only owns the Surface wrapper and GL objects created from it.
     */
    public void attachGpuNdviSurfaceTexture(
            SurfaceTexture surfaceTexture,
            int width,
            int height
    ) {
        synchronized (gpuNdviLock) {
            releaseGpuNdviRendererLocked();

            gpuNdviSurfaceTexture = surfaceTexture;
            if (surfaceTexture == null) {
                return;
            }

            try {
                surfaceTexture.setDefaultBufferSize(
                        Math.max(64, width),
                        Math.max(64, height)
                );
                gpuNdviSurface = new Surface(surfaceTexture);
            } catch (Exception e) {
                Log.e(TAG, "GPU NDVI surface setup failed", e);
                gpuNdviSurfaceTexture = null;
                gpuNdviSurface = null;
            }
        }
    }

    public void detachGpuNdviSurfaceTexture() {
        synchronized (gpuNdviLock) {
            releaseGpuNdviRendererLocked();
            gpuNdviSurfaceTexture = null;
        }
    }

    private void releaseGpuNdviRendererLocked() {
        if (gpuNdviRenderer != null) {
            try {
                gpuNdviRenderer.release();
            } catch (Exception ignored) {
            }
            gpuNdviRenderer = null;
        }

        if (gpuNdviSurface != null) {
            try {
                gpuNdviSurface.release();
            } catch (Exception ignored) {
            }
            gpuNdviSurface = null;
        }
    }


    private boolean hasWifiPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            return context.checkSelfPermission(
                    android.Manifest.permission.NEARBY_WIFI_DEVICES
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return context.checkSelfPermission(
                    android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }


    public void initialize() {
        initialized = true;
    }

    public boolean connectLastWifi() {
        if (lastSsid == null || lastSsid.trim().isEmpty()) {
            sendEvent(
                    "networkUnavailable",
                    "NO SAVED CAMERA NETWORK"
            );
            return false;
        }

        connectWifi(lastSsid, lastPassword);
        return true;
    }

    public void onNfcListeningStarted() {
        nfcListening = true;
        updateSystemStatus("WAITING FOR NFC", true);
    }

    public void onNfcListeningStopped() {
        nfcListening = false;
        updateSystemStatus("NFC READY", true);
    }

    public void handleNfcPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return;
        }

        try {
            Credentials credentials = parseSonyCredentials(payload);
            if (credentials == null) {
                return;
            }

            lastSsid = credentials.ssid;
            lastPassword = credentials.password;

            HashMap<String, Object> event = new HashMap<>();
            event.put("ssid", lastSsid);
            event.put("payloadDetected", true);
            sendEvent("nfcCredentials", event);

            connectWifi(lastSsid, lastPassword);
        } catch (Exception e) {
            Log.e(TAG, "NFC payload parsing failed", e);
            onEngineError("NFC PARSE FAILED: " + safeMessage(e), e);
        }
    }

    public void capture() {
        takePicture();
    }

    public void probeCurrentNetworkPublic() {
        probeCurrentNetwork();
    }

    public void probeCurrentNetwork() {
        probeCurrentNetworkInternal();
    }

    private void probeCurrentNetworkInternal() {
        // Method body is supplied by the existing private implementation via
        // the renamed helper inserted below.
        probeCurrentNetworkImpl();
    }

    public void refreshLiveview() {
        refreshLiveviewImpl();
    }

    public boolean toggleViewMode() {
        isQuadMode = !isQuadMode;

        HashMap<String, Object> data = new HashMap<>();
        data.put("quad", isQuadMode);
        sendEvent("viewModeChanged", data);
        return isQuadMode;
    }

    public boolean setGrayscale(boolean enabled) {
        grayscaleMode = enabled;
        sendEvent("grayscaleChanged", grayscaleMode);
        return true;
    }

    public boolean setSpectralBand(String rawBand) {
        String band = rawBand == null
                ? ""
                : rawBand.trim().toUpperCase(Locale.US);

        if (!Arrays.asList("RGB", "R", "G", "B", "NIR").contains(band)) {
            return false;
        }

        if ("NIR".equals(band) && !realNirAvailable) {
            sendEvent(
                    "engineWarning",
                    "NIR IS NOT AVAILABLE: RIGHT OPTICAL ROI WAS NOT DETECTED"
            );
            return false;
        }

        /*
         * Spectral selection and NDVI are mutually exclusive preview modes.
         * A direct band command is authoritative: it turns NDVI off first.
         *
         * This is intentionally done here instead of in startup/lifecycle
         * code, so normal camera initialization remains untouched.
         */
        if (ndviEnabled) {
            ndviEnabled = false;

            previewGeneration.incrementAndGet();

            lastNdviEventAt = 0L;
            lastNdviPreviewAt = 0L;
            lastNdviComputedAt = 0L;
            lastNdviValue = Double.NaN;
            lastNdviValidPixels = 0;
            lastNdviNirGain = 1.0f;
            lastNdviCalibrationAt = 0L;
            ndviRendererMode = NDVI_RENDERER_UNKNOWN;

            HashMap<String, Object> ndviOff =
                    new HashMap<>();
            ndviOff.put("enabled", false);
            ndviOff.put("metric", "RELATIVE_DIGITAL_NDVI");
            ndviOff.put(
                    "source",
                    "RED_LEFT_OPTICAL_ROI_VS_NIR_RIGHT_OPTICAL_ROI"
            );
            ndviOff.put("reason", "SPECTRAL_BAND_SELECTED");
            sendEvent("ndviModeChanged", ndviOff);
        }

        activeSpectralBand = band;
        spectralGeneration.incrementAndGet();
        previewProcessingSequence.set(0L);

        /*
         * Every spectral selection is a new preview generation. Any normal
         * frame already in the processing queue must prove that it belongs to
         * this generation before it can be published.
         */
        previewGeneration.incrementAndGet();

        HashMap<String, Object> data = new HashMap<>();
        data.put("band", band);
        data.put(
                "source",
                "NIR".equals(band)
                        ? "NIR_RIGHT_OPTICAL_ROI_ALIGNED_TO_UNIFIED_CROP"
                        : "RGB_LEFT_OPTICAL_ROI_UNIFIED_CROP"
        );
        data.put("realSpectralFrame", true);
        data.put("unifiedSpectralCrop", true);
        data.put("previewMode", "PROCESSED");
        sendEvent("spectralBandChanged", data);

        updateSystemStatus(
                "NIR".equals(band) ? "NIR VIEW" : band + " VIEW",
                true
        );
        return true;
    }

    public synchronized boolean setCaptureMode(String rawMode) {
        String mode = normalizeCaptureOutput(rawMode);

        if (mode == null) {
            return false;
        }

        captureMode = mode;
        previewGeneration.incrementAndGet();
        previewProcessingSequence.set(0L);

        HashMap<String, Object> data =
                new HashMap<>();

        data.put("mode", captureMode);
        data.put("captureOutput", captureMode);

        sendEvent("captureModeChanged", data);
        sendAdvancedSettingsState();

        return true;
    }

    public String getCaptureMode() {
        return captureMode;
    }

    public synchronized boolean setCaptureOutput(String rawCaptureOutput) {
        return setCaptureMode(rawCaptureOutput);
    }

    public synchronized boolean setPerformance(String rawPerformance) {
        String mode = normalizePerformanceMode(rawPerformance);

        if (mode == null) {
            return false;
        }

        performanceMode = mode;
        applyPerformancePreset();

        previewGeneration.incrementAndGet();
        previewProcessingSequence.set(0L);

        sendAdvancedSettingsState();
        return true;
    }

    public synchronized boolean setPerformance(
            String rawPerformance,
            Map<?, ?> ignoredPerformanceConfig
    ) {
        return setPerformance(rawPerformance);
    }

    public synchronized boolean applyAdvancedSettings(
            String rawCaptureOutput,
            String rawPerformance
    ) {
        return applyAdvancedSettings(
                rawCaptureOutput,
                rawPerformance,
                null
        );
    }

    public synchronized boolean applyAdvancedSettings(
            String rawCaptureOutput,
            String rawPerformance,
            Map<?, ?> ignoredPerformanceConfig
    ) {
        String mode = normalizeCaptureOutput(rawCaptureOutput);
        String performance = normalizePerformanceMode(rawPerformance);

        if (mode == null || performance == null) {
            return false;
        }

        captureMode = mode;
        performanceMode = performance;
        applyPerformancePreset();

        previewGeneration.incrementAndGet();
        previewProcessingSequence.set(0L);

        HashMap<String, Object> data =
                new HashMap<>();
        data.put("mode", captureMode);
        data.put("captureOutput", captureMode);

        sendEvent("captureModeChanged", data);
        sendAdvancedSettingsState();

        return true;
    }

    public synchronized Map<String, Object> getAdvancedSettings() {
        return new HashMap<>(performanceConfigMap());
    }

    private String normalizeCaptureOutput(String rawMode) {
        if (rawMode == null) {
            return null;
        }

        String mode =
                rawMode
                        .trim()
                        .toUpperCase(Locale.US)
                        .replace("_", " ")
                        .replace("+", " + ")
                        .replaceAll("\\s+", " ")
                        .trim();

        if (CAPTURE_MODE_RAW.equals(mode)) {
            return CAPTURE_MODE_RAW;
        }

        if (CAPTURE_MODE_PROCESSED.equals(mode)) {
            return CAPTURE_MODE_PROCESSED;
        }

        if ("RAW + PROCESSED".equals(mode)
                || "RAW PROCESSED".equals(mode)
                || "BOTH".equals(mode)) {
            return CAPTURE_MODE_BOTH;
        }

        return null;
    }

    private String normalizePerformanceMode(String rawPerformance) {
        if (rawPerformance == null) {
            return null;
        }

        String mode =
                rawPerformance
                        .trim()
                        .toUpperCase(Locale.US)
                        .replace("_", " ")
                        .replaceAll("\\s+", " ");

        if (PERFORMANCE_PERFORMANCE.equals(mode)) {
            return PERFORMANCE_PERFORMANCE;
        }

        if (PERFORMANCE_BALANCED.equals(mode)) {
            return PERFORMANCE_BALANCED;
        }

        if (PERFORMANCE_HIGH_QUALITY.equals(mode)
                || "HIGH RESOLUTION".equals(mode)
                || "QUALITY".equals(mode)) {
            return PERFORMANCE_HIGH_QUALITY;
        }

        return null;
    }

    private void applyPerformancePreset() {
        if (PERFORMANCE_PERFORMANCE.equals(performanceMode)) {
            previewScale = 0.50d;
            processingScale = 0.50d;
            processingEveryNFrames = 2;
            return;
        }

        if (PERFORMANCE_HIGH_QUALITY.equals(performanceMode)) {
            previewScale = 1.00d;
            processingScale = 1.00d;
            processingEveryNFrames = 1;
            return;
        }

        previewScale = 0.75d;
        processingScale = 0.75d;
        processingEveryNFrames = 1;
    }

    private Map<String, Object> performanceConfigMap() {
        HashMap<String, Object> result =
                new HashMap<>();

        result.put("captureOutput", captureMode);
        result.put("captureMode", captureMode);
        result.put("performance", performanceMode);
        result.put("performanceMode", performanceMode);
        result.put("previewScale", previewScale);
        result.put("processingScale", processingScale);
        result.put("processingEveryNFrames", processingEveryNFrames);

        result.put(
                "previewPolicy",
                previewScale >= 0.999d
                        ? "SOURCE"
                        : (previewScale <= 0.50d ? "LOW" : "MEDIUM")
        );
        result.put(
                "processingPolicy",
                processingScale >= 0.999d
                        ? "SOURCE"
                        : (processingScale <= 0.50d ? "LOW" : "MEDIUM")
        );
        result.put(
                "framePolicy",
                processingEveryNFrames > 1 ? "SKIP" : "FULL"
        );

        return result;
    }

    private void sendAdvancedSettingsState() {
        sendEvent(
                "advancedSettingsChanged",
                new HashMap<String, Object>(performanceConfigMap())
        );
    }

    public boolean setNdviEnabled(boolean enabled) {

        /*
         * NDVI is only valid when the dual optical source exists. This is a
         * native-side guard in addition to Flutter's capability guard.
         */
        if (enabled
                && !realNirAvailable) {

            sendEvent(
                    "engineWarning",
                    "NDVI REQUIRES RED + NIR OPTICAL ROI"
            );
            return false;
        }

        /*
         * A mode change creates a new generation before any new preview can be
         * committed. This invalidates all queued/stale spectral work.
         */
        ndviEnabled = enabled;
        previewGeneration.incrementAndGet();
        previewProcessingSequence.set(0L);

        lastNdviEventAt = 0L;
        lastNdviPreviewAt = 0L;
        lastNdviComputedAt = 0L;
        lastNdviValue = Double.NaN;
        lastNdviValidPixels = 0;
        lastNdviNirGain = 1.0f;
        lastNdviCalibrationAt = 0L;

        /*
         * A new NDVI session starts with renderer selection unknown. If the
         * GPU surface is temporarily unavailable, CPU may be used for that
         * frame without permanently locking the session. A real GPU failure
         * is handled inside renderGpuNdvi() and then the session becomes CPU.
         */
        ndviRendererMode = NDVI_RENDERER_UNKNOWN;

        lastNdviFrameEventAt = 0L;
        lastNormalFrameEventAt = 0L;

        HashMap<String, Object> data = new HashMap<>();
        data.put("enabled", ndviEnabled);
        data.put("metric", "RELATIVE_DIGITAL_NDVI");
        data.put(
                "source",
                "RED_LEFT_OPTICAL_ROI_VS_NIR_RIGHT_OPTICAL_ROI"
        );
        data.put("registration", "CENTERED_AFFINE_CALIBRATION_READY");
        data.put("normalized", true);

        sendEvent("ndviModeChanged", data);

        log(
                "INFO",
                ndviEnabled
                        ? "NDVI COMPUTATION ENABLED"
                        : "NDVI COMPUTATION DISABLED"
        );

        return true;
    }

    public boolean isNdviEnabled() {
        return ndviEnabled;
    }

    /**
     * Compatibility path for older Flutter builds. New UI does not expose
     * manual focus because CameraEngine configures continuous AF on startup.
     */
    public void triggerAutoFocus() {
        triggerAutoFocusImpl();
    }

    public void disconnect() {
        disconnectImpl();
    }


    private Credentials parseSonyCredentials(
            byte[] payload
    ) {
        if (payload == null
                || payload.length == 0) {
            return null;
        }

        String raw =
                new String(
                        payload,
                        StandardCharsets.UTF_8
                )
                        .replace(
                                '\u0000',
                                ' '
                        )
                        .trim();

        Credentials result =
                parseCredentialsFromText(
                        raw
                );

        if (result != null) {
            return result;
        }

        return parseCredentialsFromText(
                printableAscii(payload)
        );
    }

    private Credentials parseCredentialsFromText(
            String text
    ) {
        if (text == null
                || text.isEmpty()) {
            return null;
        }

        Matcher ssidMatcher =
                SONY_SSID_PATTERN.matcher(
                        text
                );

        if (!ssidMatcher.find()) {
            return null;
        }

        String ssid =
                ssidMatcher.group();

        String password = null;

        Matcher explicitPassword =
                PASSWORD_PATTERN.matcher(
                        text
                );

        if (explicitPassword.find()) {
            password =
                    explicitPassword.group(1);
        }

        if (password == null) {

            Matcher digits =
                    EIGHT_DIGIT_PATTERN.matcher(
                            text
                    );

            while (digits.find()) {

                String candidate =
                        digits.group();

                if (!candidate.equals(
                        ssid
                )) {
                    password =
                            candidate;
                }
            }
        }

        if (password == null
                && text.length() >= 8) {

            String tail =
                    text.substring(
                            text.length() - 8
                    ).trim();

            if (tail.matches(
                    "[A-Za-z0-9]{8}"
            )) {
                password = tail;
            }
        }

        if (password == null
                || password.isEmpty()) {

            return null;
        }

        return new Credentials(
                ssid,
                password
        );
    }

    private String printableAscii(
            byte[] bytes
    ) {
        StringBuilder builder =
                new StringBuilder(
                        bytes.length
                );

        for (byte value : bytes) {

            int c = value & 0xFF;

            if (
                    (c >= 32 && c <= 126)
                            || c == '\n'
                            || c == '\r'
                            || c == '\t'
            ) {

                builder.append(
                        (char) c
                );

            } else {

                builder.append(' ');
            }
        }

        return builder.toString();
    }

    private void connectWifi(
            String ssid,
            String password
    ) {

        if (ssid == null
                || ssid.trim().isEmpty()) {

            sendEvent(
                    "networkUnavailable",
                    "NO CAMERA SSID"
            );

            updateSystemStatus(
                    "CAMERA SSID MISSING",
                    false
            );

            return;
        }

        if (Build.VERSION.SDK_INT
                < Build.VERSION_CODES.Q) {

            sendEvent(
                    "networkUnavailable",
                    "ANDROID 10+ REQUIRED"
            );

            updateSystemStatus(
                    "WIFI API NOT SUPPORTED",
                    false
            );

            return;
        }

        if (!hasWifiPermission()) {

            sendEvent(
                    "networkUnavailable",
                    "WIFI PERMISSION NOT GRANTED"
            );

            updateSystemStatus(
                    "WIFI PERMISSION REQUIRED",
                    false
            );

            return;
        }

        final long generation =
                sessionGeneration.incrementAndGet();

        stopStreaming();
        stopNirPolling();

        releaseMulticastLock();

        isEngineStarting.set(
                false
        );

        isDiscoveryRunning.set(
                false
        );

        clearCameraEndpoint();

        updateSystemStatus(
                "CONNECTING",
                true
        );

        sendEvent(
                "wifiConnecting",
                ssid
        );

        log(
                "INFO",
                "Requesting camera Wi-Fi: "
                        + ssid
        );

        final WifiNetworkSpecifier specifier;

        try {

            WifiNetworkSpecifier.Builder builder =
                    new WifiNetworkSpecifier.Builder()
                            .setSsid(ssid);

            if (password != null
                    && !password.isEmpty()) {

                builder.setWpa2Passphrase(
                        password
                );
            }

            specifier =
                    builder.build();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Wi-Fi specifier creation failed",
                    e
            );

            sendEvent(
                    "error",
                    "WIFI SPECIFIER FAILED: "
                            + safeMessage(e)
            );

            updateSystemStatus(
                    "WIFI SPECIFIER FAILED",
                    false
            );

            return;
        }

        NetworkRequest request =
                new NetworkRequest.Builder()
                        .addTransportType(
                                NetworkCapabilities
                                        .TRANSPORT_WIFI
                        )
                        .removeCapability(
                                NetworkCapabilities
                                        .NET_CAPABILITY_INTERNET
                        )
                        .setNetworkSpecifier(
                                specifier
                        )
                        .build();

        connectivityManager =
                (ConnectivityManager)
                        context.getSystemService(
                                Context.CONNECTIVITY_SERVICE
                        );

        if (connectivityManager == null) {

            sendEvent(
                    "error",
                    "CONNECTIVITY SERVICE UNAVAILABLE"
            );

            return;
        }

        unregisterCurrentNetworkCallback();

        networkCallback =
                new ConnectivityManager.NetworkCallback() {

                    @Override
                    public void onAvailable(
                            Network network
                    ) {

                        currentNetwork =
                                network;

                        try {

                            connectivityManager
                                    .bindProcessToNetwork(
                                            network
                                    );

                        } catch (
                                Exception bindError
                        ) {

                            Log.w(
                                    TAG,
                                    "Process network bind failed",
                                    bindError
                            );

                            sendEvent(
                                    "engineWarning",
                                    "PROCESS NETWORK BIND FAILED; "
                                            + "USING EXPLICIT CAMERA NETWORK"
                            );
                        }

                        updateSystemStatus(
                                "NETWORK READY",
                                true
                        );

                        sendEvent(
                                "wifiConnected",
                                ssid
                        );

                        logNetworkDetails(
                                network
                        );

                        executor.execute(
                                () -> {

                                    waitForNetworkProperties(
                                            network,
                                            2200L
                                    );

                                    if (!isCurrentSession(
                                            generation,
                                            network
                                    )) {
                                        return;
                                    }

                                    discoverAndStartCamera(
                                            network,
                                            generation
                                    );
                                }
                        );
                    }

                    @Override
                    public void onLost(
                            Network network
                    ) {

                        sessionGeneration.incrementAndGet();

                        if (currentNetwork
                                == network) {

                            currentNetwork =
                                    null;
                        }

                        stopStreaming();
                        clearCameraEndpoint();

                        updateSystemStatus(
                                "CAMERA NETWORK LOST",
                                false
                        );

                        sendEvent(
                                "networkLost",
                                ssid
                        );
                    }

                    @Override
                    public void onUnavailable() {

                        sessionGeneration.incrementAndGet();

                        currentNetwork =
                                null;

                        stopStreaming();
                        releaseMulticastLock();

                        updateSystemStatus(
                                "CAMERA NETWORK UNAVAILABLE",
                                false
                        );

                        sendEvent(
                                "networkUnavailable",
                                ssid
                        );

                        log(
                                "ERROR",
                                "Camera Wi-Fi request became unavailable"
                        );
                    }
                };

        try {

            connectivityManager.requestNetwork(
                    request,
                    networkCallback
            );

        } catch (SecurityException e) {

            Log.e(
                    TAG,
                    "requestNetwork security failure",
                    e
            );

            sendEvent(
                    "error",
                    "WIFI NETWORK PERMISSION DENIED"
            );

            updateSystemStatus(
                    "WIFI PERMISSION DENIED",
                    false
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "requestNetwork failed",
                    e
            );

            sendEvent(
                    "error",
                    "WIFI CONNECTION FAILED: "
                            + safeMessage(e)
            );

            updateSystemStatus(
                    "WIFI CONNECTION FAILED",
                    false
            );
        }
    }

    private void probeCurrentNetworkImpl() {

        ConnectivityManager cm =
                (ConnectivityManager)
                        context.getSystemService(
                                Context.CONNECTIVITY_SERVICE
                        );

        if (cm == null) {

            sendEvent(
                    "networkUnavailable",
                    "CONNECTIVITY SERVICE UNAVAILABLE"
            );

            return;
        }

        Network network =
                currentNetwork;

        if (network == null) {
            network =
                    cm.getActiveNetwork();
        }

        if (network == null) {

            sendEvent(
                    "networkUnavailable",
                    "NO ACTIVE NETWORK"
            );

            updateSystemStatus(
                    "NO ACTIVE NETWORK",
                    false
            );

            return;
        }

        currentNetwork =
                network;

        Network target =
                network;

        long generation =
                sessionGeneration.incrementAndGet();

        executor.execute(
                () -> {

                    waitForNetworkProperties(
                            target,
                            1500L
                    );

                    if (!isCurrentSession(
                            generation,
                            target
                    )) {
                        return;
                    }

                    discoverAndStartCamera(
                            target,
                            generation
                    );
                }
        );
    }

    private void waitForNetworkProperties(
            Network network,
            long timeoutMs
    ) {

        long deadline =
                System.currentTimeMillis()
                        + Math.max(
                                0L,
                                timeoutMs
                        );

        while (
                System.currentTimeMillis()
                        < deadline
        ) {

            LinkProperties properties =
                    getLinkProperties(
                            network
                    );

            if (
                    properties != null
                            && hasUsableIpv4(
                            properties
                    )
            ) {
                return;
            }

            sleepQuietly(150L);
        }
    }

    private boolean hasUsableIpv4(
            LinkProperties properties
    ) {

        if (properties == null) {
            return false;
        }

        for (
                LinkAddress address
                : properties.getLinkAddresses()
        ) {

            if (
                    address.getAddress()
                            instanceof Inet4Address
            ) {

                Inet4Address ipv4 =
                        (Inet4Address)
                                address.getAddress();

                if (
                        !ipv4.isLoopbackAddress()
                                && !ipv4.isAnyLocalAddress()
                ) {

                    return true;
                }
            }
        }

        return false;
    }

    private void discoverAndStartCamera(
            Network network,
            long generation
    ) {

        if (!isCurrentSession(
                generation,
                network
        )) {
            return;
        }

        if (network == null) {

            updateSystemStatus(
                    "NO CAMERA NETWORK",
                    false
            );

            return;
        }

        if (!isDiscoveryRunning.compareAndSet(
                false,
                true
        )) {

            log(
                    "INFO",
                    "Camera discovery already running"
            );

            return;
        }

        firstFrameSent =
                false;

        previewGeneration.incrementAndGet();
        ndviRendererMode =
                NDVI_RENDERER_UNKNOWN;
        lastNormalFrameEventAt = 0L;
        lastNdviFrameEventAt = 0L;

        ndviEnabled = false;
        lastNdviEventAt = 0L;
        lastNdviValue = Double.NaN;
        lastNdviValidPixels = 0;
        lastNdviNirGain = 1.0f;

        updateSystemStatus(
                "DISCOVERING CAMERA",
                true
        );

        sendEvent(
                "cameraProbe",
                "DISCOVERY_STARTED"
        );

        log(
                "INFO",
                "Camera discovery started"
        );

        try {

            CameraEndpoint endpoint =
                    discoveredEndpoint.getAndSet(
                            null
                    );

            if (!isCurrentSession(
                    generation,
                    network
            )) {
                return;
            }

            if (endpoint == null) {
                endpoint =
                        discoverSonyViaSsdp(
                                network
                        );
            }

            if (!isCurrentSession(
                    generation,
                    network
            )) {
                return;
            }

            if (endpoint == null) {
                endpoint =
                        discoverSonyViaDescription(
                                network
                        );
            }

            if (!isCurrentSession(
                    generation,
                    network
            )) {
                return;
            }

            if (endpoint == null) {
                endpoint =
                        discoverSonyViaNetworkScan(
                                network
                        );
            }

            if (!isCurrentSession(
                    generation,
                    network
            )) {
                return;
            }

            if (endpoint == null) {

                clearCameraEndpoint();

                updateSystemStatus(
                        "CAMERA NOT FOUND",
                        false
                );

                sendEvent(
                        "cameraError",
                        "NO SUPPORTED CAMERA API FOUND "
                                + "ON THIS NETWORK"
                );

                log(
                        "ERROR",
                        "No supported camera API endpoint found"
                );

                return;
            }

            setCameraEndpoint(
                    endpoint
            );

            sendEndpointFound(
                    endpoint
            );

            probeAndStartCamera(
                    network,
                    endpoint,
                    generation
            );

        } finally {

            isDiscoveryRunning.set(
                    false
            );
        }
    }

    private CameraEndpoint discoverSonyViaSsdp(
            Network network
    ) {

        log(
                "INFO",
                "Trying SSDP camera discovery"
        );

        acquireMulticastLock();

        try {

            String[] searchTargets =
                    new String[]{
                            SONY_SCALAR_ST,
                            "ssdp:all"
                    };

            for (
                    String st
                    : searchTargets
            ) {

                try {

                    List<String> locations =
                            ssdpSearch(
                                    network,
                                    st
                            );

                    for (
                            String location
                            : locations
                    ) {

                        log(
                                "INFO",
                                "SSDP LOCATION: "
                                        + location
                        );

                        CameraEndpoint endpoint =
                                endpointFromDescriptionLocation(
                                        network,
                                        location
                                );

                        if (endpoint != null) {

                            log(
                                    "INFO",
                                    "Camera API discovered via SSDP"
                            );

                            return endpoint;
                        }
                    }

                } catch (Exception e) {

                    log(
                            "WARN",
                            "SSDP discovery failed for "
                                    + st
                                    + ": "
                                    + safeMessage(e)
                    );
                }
            }

        } finally {

            releaseMulticastLock();
        }

        return null;
    }

    private List<String> ssdpSearch(
            Network network,
            String searchTarget
    ) throws Exception {

        LinkedHashSet<String> locations =
                new LinkedHashSet<>();

        String requestText =
                "M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: "
                        + SSDP_ADDRESS
                        + ":"
                        + SSDP_PORT
                        + "\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 1\r\n"
                        + "ST: "
                        + searchTarget
                        + "\r\n"
                        + "USER-AGENT: LANDCAM/1.0 Android\r\n"
                        + "\r\n";

        byte[] requestBytes =
                requestText.getBytes(
                        StandardCharsets.UTF_8
                );

        InetAddress multicast =
                InetAddress.getByName(
                        SSDP_ADDRESS
                );

        long deadline =
                System.currentTimeMillis()
                        + SSDP_TIMEOUT_MS;

        try (
                DatagramSocket socket =
                        new DatagramSocket()
        ) {

            try {

                network.bindSocket(
                        socket
                );

            } catch (
                    Exception bindError
            ) {

                log(
                        "WARN",
                        "Could not bind SSDP socket "
                                + "to camera network: "
                                + safeMessage(bindError)
                );
            }

            socket.setSoTimeout(350);

            DatagramPacket searchPacket =
                    new DatagramPacket(
                            requestBytes,
                            requestBytes.length,
                            multicast,
                            SSDP_PORT
                    );

            socket.send(
                    searchPacket
            );

            sleepQuietly(60L);

            socket.send(
                    searchPacket
            );

            byte[] buffer =
                    new byte[16384];

            while (
                    System.currentTimeMillis()
                            < deadline
            ) {

                try {

                    DatagramPacket packet =
                            new DatagramPacket(
                                    buffer,
                                    buffer.length
                            );

                    socket.receive(
                            packet
                    );

                    String response =
                            new String(
                                    packet.getData(),
                                    packet.getOffset(),
                                    packet.getLength(),
                                    StandardCharsets.UTF_8
                            );

                    Matcher locationMatcher =
                            SSDP_LOCATION_PATTERN
                                    .matcher(
                                            response
                                    );

                    while (
                            locationMatcher.find()
                    ) {

                        String location =
                                locationMatcher
                                        .group(1)
                                        .trim();

                        if (!location.isEmpty()) {
                            locations.add(
                                    location
                            );
                        }
                    }

                } catch (
                        java.net.SocketTimeoutException timeout
                ) {
                    // Continue until SSDP window expires.
                }
            }
        }

        return new ArrayList<>(
                locations
        );
    }

    private CameraEndpoint endpointFromDescriptionLocation(
            Network network,
            String location
    ) {

        if (location == null
                || location.trim().isEmpty()) {
            return null;
        }

        try {

            URL url =
                    new URL(
                            location.trim()
                    );

            String xml =
                    readText(
                            network,
                            url,
                            HTTP_PROBE_TIMEOUT_MS
                    );

            if (xml == null
                    || xml.trim().isEmpty()) {
                return null;
            }

            String friendlyName =
                    firstXmlValue(
                            XML_FRIENDLY_NAME_PATTERN,
                            xml
                    );

            String modelName =
                    firstXmlValue(
                            XML_MODEL_NAME_PATTERN,
                            xml
                    );

            String baseUrl =
                    firstXmlValue(
                            XML_BASE_URL_PATTERN,
                            xml
                    );

            List<String> actionUrls =
                    new ArrayList<>();

            if (baseUrl != null
                    && !baseUrl.isEmpty()) {

                actionUrls.add(
                        baseUrl
                );
            }

            Matcher matcher =
                    XML_ACTION_URL_PATTERN
                            .matcher(xml);

            while (matcher.find()) {

                String actionUrl =
                        cleanXmlValue(
                                matcher.group(1)
                        );

                if (
                        actionUrl != null
                                && !actionUrl.isEmpty()
                ) {

                    actionUrls.add(
                            actionUrl
                    );
                }
            }

            for (
                    String actionUrl
                    : actionUrls
            ) {

                List<String> apiCandidates =
                        buildCameraApiCandidates(
                                actionUrl
                        );

                for (
                        String apiUrl
                        : apiCandidates
                ) {

                    String normalizedApiUrl =
                            normalizeApiUrl(
                                    apiUrl
                            );

                    int apiPort =
                            effectivePort(
                                    normalizedApiUrl,
                                    url.getPort()
                            );

                    String apiScheme =
                            schemeFromUrl(
                                    normalizedApiUrl
                            );

                    String apiHost =
                            url.getHost();

                    try {

                        URL parsedApi =
                                new URL(
                                        normalizedApiUrl
                                );

                        if (
                                parsedApi.getHost()
                                        != null
                                        && !parsedApi
                                        .getHost()
                                        .isEmpty()
                        ) {

                            apiHost =
                                    parsedApi.getHost();
                        }

                    } catch (
                            Exception ignored
                    ) {
                    }

                    CameraEndpoint endpoint =
                            new CameraEndpoint(
                                    apiHost,
                                    apiPort,
                                    apiScheme,
                                    normalizedApiUrl,
                                    friendlyName,
                                    modelName
                            );

                    if (
                            probeSonyApi(
                                    network,
                                    endpoint
                            )
                    ) {

                        return endpoint;
                    }
                }
            }

        } catch (Exception e) {

            log(
                    "WARN",
                    "Description fetch failed: "
                            + safeMessage(e)
            );
        }

        return null;
    }

    private CameraEndpoint discoverSonyViaDescription(
            Network network
    ) {

        LinkedHashSet<String> hosts =
                new LinkedHashSet<>(
                        collectCandidateHosts(
                                network
                        )
                );

        log(
                "INFO",
                "Trying direct camera device-description discovery"
        );

        for (
                String host
                : hosts
        ) {

            for (
                    int port
                    : SONY_DESCRIPTION_PORTS
            ) {

                for (
                        String path
                        : DESCRIPTION_PATHS
                ) {

                    try {

                        URL url =
                                new URL(
                                        "http",
                                        host,
                                        port,
                                        path
                                );

                        String xml =
                                readText(
                                        network,
                                        url,
                                        HTTP_PROBE_TIMEOUT_MS
                                );

                        if (
                                xml == null
                                        || xml.trim().isEmpty()
                        ) {
                            continue;
                        }

                        String actionUrl =
                                chooseActionUrl(
                                        xml
                                );

                        if (actionUrl == null) {
                            continue;
                        }

                        String friendlyName =
                                firstXmlValue(
                                        XML_FRIENDLY_NAME_PATTERN,
                                        xml
                                );

                        String modelName =
                                firstXmlValue(
                                        XML_MODEL_NAME_PATTERN,
                                        xml
                                );

                        for (
                                String apiUrl
                                : buildCameraApiCandidates(
                                actionUrl
                        )) {

                            CameraEndpoint endpoint =
                                    new CameraEndpoint(
                                            host,
                                            port,
                                            "http",
                                            normalizeApiUrl(
                                                    apiUrl
                                            ),
                                            friendlyName,
                                            modelName
                                    );

                            if (
                                    probeSonyApi(
                                            network,
                                            endpoint
                                    )
                            ) {

                                log(
                                        "INFO",
                                        "Camera API found via device description"
                                );

                                return endpoint;
                            }
                        }

                    } catch (Exception ignored) {
                    }
                }
            }
        }

        return null;
    }

    private CameraEndpoint discoverSonyViaNetworkScan(
            Network network
    ) {

        List<String> hosts =
                collectFullScanHosts(
                        network
                );

        if (hosts.isEmpty()) {
            return null;
        }

        log(
                "INFO",
                "Trying network endpoint scan: "
                        + hosts.size()
                        + " hosts"
        );

        long deadline =
                System.currentTimeMillis()
                        + DISCOVERY_TOTAL_TIMEOUT_MS;

        CompletionService<CameraEndpoint>
                completionService =
                new ExecutorCompletionService<>(
                        discoveryExecutor
                );

        List<Future<CameraEndpoint>> futures =
                new ArrayList<>();

        for (
                String host
                : hosts
        ) {

            futures.add(
                    completionService.submit(
                            () ->
                                    probeHostForSony(
                                            network,
                                            host
                                    )
                    )
            );
        }

        int completed = 0;

        while (
                completed < futures.size()
                        && System.currentTimeMillis()
                        < deadline
        ) {

            long remaining =
                    deadline
                            - System.currentTimeMillis();

            try {

                Future<CameraEndpoint> future =
                        completionService.poll(
                                Math.max(
                                        1L,
                                        remaining
                                ),
                                TimeUnit.MILLISECONDS
                        );

                if (future == null) {
                    break;
                }

                completed++;

                CameraEndpoint endpoint =
                        future.get();

                if (endpoint != null) {

                    for (
                            Future<CameraEndpoint> pending
                            : futures
                    ) {

                        if (!pending.isDone()) {
                            pending.cancel(
                                    true
                            );
                        }
                    }

                    return endpoint;
                }

            } catch (Exception ignored) {

                completed++;
            }
        }

        for (
                Future<CameraEndpoint> future
                : futures
        ) {

            if (!future.isDone()) {
                future.cancel(true);
            }
        }

        return null;
    }

    private CameraEndpoint probeHostForSony(
            Network network,
            String host
    ) {

        for (
                int port
                : SONY_API_PORTS
        ) {

            if (
                    Thread.currentThread()
                            .isInterrupted()
            ) {
                return null;
            }

            if (
                    !probeTcp(
                            network,
                            host,
                            port,
                            TCP_PROBE_TIMEOUT_MS
                    )
            ) {
                continue;
            }

            for (
                    String apiPath
                    : new String[]{
                            "/sony/camera",
                            "/camera",
                            "/sony"
                    }
            ) {

                CameraEndpoint endpoint =
                        new CameraEndpoint(
                                host,
                                port,
                                port == 443
                                        ? "https"
                                        : "http",
                                (
                                        port == 443
                                                ? "https"
                                                : "http"
                                )
                                        + "://"
                                        + host
                                        + ":"
                                        + port
                                        + apiPath,
                                "",
                                ""
                        );

                if (
                        probeSonyApi(
                                network,
                                endpoint
                        )
                ) {

                    return endpoint;
                }
            }
        }

        return null;
    }

    private boolean probeTcp(
            Network network,
            String host,
            int port,
            int timeoutMs
    ) {

        try (
                Socket socket =
                        new Socket()
        ) {

            try {

                network.bindSocket(
                        socket
                );

            } catch (Exception bindError) {
                // Continue with the explicit network transport.
            }

            socket.connect(
                    new InetSocketAddress(
                            host,
                            port
                    ),
                    timeoutMs
            );

            return true;

        } catch (Exception e) {

            return false;
        }
    }

    private boolean probeSonyApi(
            Network network,
            CameraEndpoint endpoint
    ) {

        try {

            JSONObject applicationInfo =
                    callApiAt(
                            network,
                            endpoint,
                            "getApplicationInfo",
                            new JSONArray(),
                            API_CONNECT_TIMEOUT_MS,
                            API_READ_TIMEOUT_MS
                    );

            if (applicationInfo == null) {
                return false;
            }

            if (hasApiResult(
                    applicationInfo
            )) {

                applyCameraIdentity(
                        applicationInfo,
                        endpoint
                );

                return true;
            }

            JSONObject apiList =
                    callApiAt(
                            network,
                            endpoint,
                            "getAvailableApiList",
                            new JSONArray(),
                            HTTP_PROBE_TIMEOUT_MS,
                            HTTP_PROBE_TIMEOUT_MS + 1500
                    );

            if (
                    apiList != null
                            && hasApiResult(
                            apiList
                    )
            ) {

                applyCameraIdentity(
                        apiList,
                        endpoint
                );

                return true;
            }

        } catch (Exception ignored) {
        }

        return false;
    }

    private void applyCameraIdentity(
            JSONObject response,
            CameraEndpoint endpoint
    ) {

        /*
         * Internal detection is kept.
         *
         * UI-facing protocol remains generic.
         */
        cameraBrand = "INTERNAL_CAMERA";
        cameraProtocol =
                UI_PROTOCOL_LABEL;

        String model =
                findFirstString(
                        response,
                        "modelName",
                        "model",
                        "productName",
                        "modelNumber"
                );

        if (model != null
                && !model.isEmpty()) {

            cameraModel =
                    model;

        } else if (
                endpoint.modelName != null
                        && !endpoint.modelName.isEmpty()
        ) {

            cameraModel =
                    endpoint.modelName;
        }

        if (
                endpoint.friendlyName != null
                        && !endpoint.friendlyName.isEmpty()
        ) {

            /*
             * Kept only internally.
             *
             * Never emitted to Flutter.
             */
            cameraFriendlyName =
                    endpoint.friendlyName;
        }
    }

    private void setCameraEndpoint(
            CameraEndpoint endpoint
    ) {

        discoveredEndpoint.set(
                endpoint
        );

        cameraHost =
                endpoint.host;

        cameraPort =
                endpoint.port;

        cameraScheme =
                endpoint.scheme;

        cameraApiUrl =
                endpoint.apiUrl;

        /*
         * Internal only.
         */
        cameraBrand =
                "INTERNAL_CAMERA";

        cameraProtocol =
                UI_PROTOCOL_LABEL;

        if (endpoint.modelName != null
                && !endpoint.modelName.isEmpty()) {

            cameraModel =
                    endpoint.modelName;
        }

        if (endpoint.friendlyName != null
                && !endpoint.friendlyName.isEmpty()) {

            cameraFriendlyName =
                    endpoint.friendlyName;
        }
    }

    private void sendEndpointFound(
            CameraEndpoint endpoint
    ) {

        HashMap<String, Object> data =
                new HashMap<>();

        data.put(
                "host",
                endpoint.host
        );

        data.put(
                "port",
                endpoint.port
        );

        data.put(
                "scheme",
                endpoint.scheme
        );

        data.put(
                "apiUrl",
                endpoint.apiUrl
        );

        /*
         * Generic protocol only.
         *
         * IMPORTANT:
         * No brand/model/friendlyName here.
         */
        data.put(
                "protocol",
                UI_PROTOCOL_LABEL
        );

        sendEvent(
                "cameraEndpointFound",
                data
        );

        log(
                "INFO",
                "Camera endpoint selected"
        );
    }

    private void probeAndStartCamera(
            Network network,
            CameraEndpoint endpoint,
            long generation
    ) {

        executor.execute(
                () -> {

                    if (!isCurrentSession(
                            generation,
                            network
                    )) {
                        return;
                    }

                    if (!isEngineStarting
                            .compareAndSet(
                                    false,
                                    true
                            )) {

                        log(
                                "INFO",
                                "Camera engine already starting"
                        );

                        return;
                    }

                    try {

                        if (!isCurrentSession(
                                generation,
                                network
                        )) {
                            return;
                        }

                        updateSystemStatus(
                                "CAMERA REACHED",
                                true
                        );

                        sendEvent(
                                "cameraProbe",
                                "CAMERA_API_REACHED"
                        );

                        JSONObject appInfo =
                                callApiAt(
                                        network,
                                        endpoint,
                                        "getApplicationInfo",
                                        new JSONArray(),
                                        API_CONNECT_TIMEOUT_MS,
                                        API_READ_TIMEOUT_MS
                                );

                        if (appInfo != null) {

                            applyCameraIdentity(
                                    appInfo,
                                    endpoint
                            );

                            emitIdentity();
                        }

                        JSONObject available =
                                null;

                        try {

                            available =
                                    callApiAt(
                                            network,
                                            endpoint,
                                            "getAvailableApiList",
                                            new JSONArray(),
                                            API_CONNECT_TIMEOUT_MS,
                                            API_READ_TIMEOUT_MS
                                    );

                        } catch (Exception e) {

                            log(
                                    "WARN",
                                    "Available API list failed: "
                                            + safeMessage(e)
                            );
                        }

                        Set<String> supportedMethods =
                                extractMethodNames(
                                        available
                                );

                        emitCapabilities(
                                supportedMethods
                        );

                        if (!isCurrentSession(
                                generation,
                                network
                        )) {
                            return;
                        }

                        if (
                                supportedMethods.isEmpty()
                                        || supportedMethods.contains(
                                        "startRecMode"
                                )
                        ) {

                            JSONObject rec =
                                    callApiAt(
                                            network,
                                            endpoint,
                                            "startRecMode",
                                            new JSONArray(),
                                            API_CONNECT_TIMEOUT_MS,
                                            API_READ_TIMEOUT_MS
                                    );

                            if (
                                    hasApiError(rec)
                                            && !isBenignAlreadyActiveError(
                                            rec
                                    )
                            ) {

                                throw new IllegalStateException(
                                        "startRecMode failed: "
                                                + apiErrorDescription(rec)
                                );
                            }
                        }

                        sleepQuietly(
                                850L
                        );

                        if (
                                supportedMethods.isEmpty()
                                        || supportedMethods.contains(
                                        "setFocusMode"
                                )
                        ) {

                            try {

                                JSONObject focus =
                                        trySetBestFocusMode(
                                                network,
                                                endpoint
                                        );

                                if (
                                        focus != null
                                                && hasApiError(
                                                focus
                                        )
                                ) {

                                    log(
                                            "WARN",
                                            "Autofocus mode unavailable: "
                                                    + apiErrorDescription(
                                                    focus
                                            )
                                    );
                                }

                            } catch (Exception e) {

                                log(
                                        "WARN",
                                        "Focus mode setup failed: "
                                                + safeMessage(e)
                                );
                            }
                        }

                        if (
                                supportedMethods.isEmpty()
                                        || supportedMethods.contains(
                                        "setLiveviewSize"
                                )
                        ) {

                            try {

                                JSONArray params =
                                        new JSONArray();

                                params.put(
                                        "L"
                                );

                                JSONObject size =
                                        callApiAt(
                                                network,
                                                endpoint,
                                                "setLiveviewSize",
                                                params,
                                                API_CONNECT_TIMEOUT_MS,
                                                API_READ_TIMEOUT_MS
                                        );

                                if (
                                        hasApiError(size)
                                ) {

                                    log(
                                            "WARN",
                                            "Live View size control unavailable: "
                                                    + apiErrorDescription(
                                                    size
                                            )
                                    );
                                }

                            } catch (Exception e) {

                                log(
                                        "WARN",
                                        "Live View size command failed: "
                                                + safeMessage(e)
                                );
                            }
                        }

                        if (!isCurrentSession(
                                generation,
                                network
                        )) {
                            return;
                        }

                        updateSystemStatus(
                                "STARTING LIVE VIEW",
                                true
                        );

                        sendEvent(
                                "cameraProbe",
                                "LIVEVIEW_REQUEST"
                        );

                        try {

                            callApiAt(
                                    network,
                                    endpoint,
                                    "stopLiveview",
                                    new JSONArray(),
                                    API_CONNECT_TIMEOUT_MS,
                                    API_READ_TIMEOUT_MS
                            );

                        } catch (Exception ignored) {
                        }

                        JSONObject liveview =
                                callApiAt(
                                        network,
                                        endpoint,
                                        "startLiveview",
                                        new JSONArray(),
                                        API_CONNECT_TIMEOUT_MS,
                                        API_READ_TIMEOUT_MS
                                );

                        if (
                                liveview == null
                                        || hasApiError(
                                        liveview
                                )
                        ) {

                            throw new IllegalStateException(
                                    "startLiveview failed: "
                                            + apiErrorDescription(
                                            liveview
                                    )
                            );
                        }

                        String streamUrl =
                                findUrlInJson(
                                        liveview
                                );

                        if (
                                streamUrl == null
                                        || streamUrl.isEmpty()
                        ) {

                            throw new IllegalStateException(
                                    "NO LIVEVIEW URL"
                            );
                        }

                        lastLiveviewUrl =
                                normalizeStreamUrl(
                                        streamUrl,
                                        endpoint
                                );

                        emitCapabilities(
                                supportedMethods
                        );

                        if (!isCurrentSession(
                                generation,
                                network
                        )) {
                            return;
                        }

                        startStreaming(
                                lastLiveviewUrl,
                                "RGB"
                        );

                    } catch (Exception e) {

                        Log.e(
                                TAG,
                                "Camera engine error",
                                e
                        );

                        stopStreaming();

                        updateSystemStatus(
                                "ENGINE ERROR",
                                false
                        );

                        sendEvent(
                                "cameraError",
                                "CAMERA ENGINE FAILED: "
                                        + safeMessage(e)
                        );

                        log(
                                "ERROR",
                                "Camera engine failed: "
                                        + safeMessage(e)
                        );

                    } finally {

                        isEngineStarting.set(
                                false
                        );
                    }
                }
        );
    }

    private void emitIdentity() {

        HashMap<String, Object> data =
                new HashMap<>();

        /*
         * Only generic connection information is
         * sent to Flutter.
         *
         * NO:
         * - brand
         * - model
         * - friendlyName
         */
        data.put(
                "protocol",
                UI_PROTOCOL_LABEL
        );

        data.put(
                "host",
                cameraHost == null
                        ? ""
                        : cameraHost
        );

        data.put(
                "port",
                cameraPort
        );

        sendEvent(
                "cameraIdentified",
                data
        );
    }

    private void emitCapabilities(
            Set<String> apiMethods
    ) {

        boolean autofocus =
                supportsAny(
                        apiMethods,
                        "actFocus",
                        "actHalfPressShutter",
                        "setFocusMode",
                        "getFocusMode",
                        "getAvailableFocusMode",
                        "setTouchAFPosition"
                );

        if (apiMethods == null || apiMethods.isEmpty()) {
            autofocus = true;
        }

        ArrayList<String> bands = new ArrayList<>();
        bands.add("RGB");
        bands.add("R");
        bands.add("G");
        bands.add("B");
        if (realNirAvailable) {
            bands.add("NIR");
        }

        HashMap<String, Object> data = new HashMap<>();
        data.put("bands", bands);
        data.put("spectralSource", "UNIFIED DUAL-OPTICAL REGISTERED CROP");
        data.put("realNirAvailable", realNirAvailable);
        data.put("rawBayerStreamAvailable", false);
        data.put(
                "nirSource",
                realNirAvailable
                        ? "RIGHT OPTICAL ROI -> UNIFIED CROP"
                        : "NONE"
        );
        data.put("protocol", UI_PROTOCOL_LABEL);
        data.put(
                "liveView",
                supports(apiMethods, "startLiveview")
        );
        data.put(
                "capture",
                supports(apiMethods, "actTakePicture")
        );
        data.put("autofocus", autofocus);
        data.put("dualOpticalRoi", realNirAvailable);
        data.put("unifiedSpectralCrop", realNirAvailable);
        data.put("rgbSource", "LEFT OPTICAL ROI -> UNIFIED CROP");

        sendEvent("cameraCapabilities", data);
    }

    private boolean supports(
            Set<String> methods,
            String method
    ) {

        return methods == null
                || methods.isEmpty()
                || methods.contains(
                normalizeApiMethodName(method)
        );
    }

    private boolean supportsAny(
            Set<String> methods,
            String... candidates
    ) {

        if (methods == null || methods.isEmpty()) {
            return true;
        }

        if (candidates == null) {
            return false;
        }

        for (String candidate : candidates) {
            if (candidate != null
                    && methods.contains(
                    normalizeApiMethodName(candidate)
            )) {
                return true;
            }
        }

        return false;
    }

    private void probeNirMethodsWhenCapabilityListUnavailable(
            Network network,
            CameraEndpoint endpoint
    ) {

        if (network == null || endpoint == null || realNirAvailable) {
            return;
        }

        if (currentNetwork != network
                || cameraApiUrl == null
                || endpoint.apiUrl == null
                || !cameraApiUrl.equals(endpoint.apiUrl)) {
            return;
        }

        for (String candidate : NIR_METHOD_CANDIDATES) {

            if (Thread.currentThread().isInterrupted()) {
                return;
            }

            try {

                JSONObject response =
                        callApiAt(
                                network,
                                endpoint,
                                candidate,
                                new JSONArray(),
                                HTTP_PROBE_TIMEOUT_MS,
                                HTTP_PROBE_TIMEOUT_MS + 1500
                        );

                if (response != null && !hasApiError(response)) {
                    String url =
                            findUrlInJson(response);

                    // A successful custom NIR method is enough to classify the
                    // camera as NIR-capable. The frame activation step will
                    // validate the actual image/stream payload.
                    if (url != null && !url.isEmpty()) {
                        nirApiMethod = candidate;
                        realNirAvailable = true;
                        emitCapabilities(
                                Collections.emptySet()
                        );
                        log(
                                "INFO",
                                "Dedicated NIR API discovered: "
                                        + candidate
                        );
                        return;
                    }
                }

            } catch (Exception ignored) {
            }
        }
    }

    private boolean detectNirCapability(
            Set<String> methods
    ) {

        realNirAvailable = false;
        nirApiMethod = null;

        if (methods == null || methods.isEmpty()) {
            return false;
        }

        // Prefer explicit NIR/infrared aliases in the known order.
        for (String candidate : NIR_METHOD_CANDIDATES) {
            String normalized =
                    normalizeApiMethodName(candidate);

            if (methods.contains(normalized)) {
                nirApiMethod = normalized;
                realNirAvailable = true;
                break;
            }
        }

        // Also accept future/custom camera APIs that clearly declare an NIR
        // or infrared method, without treating generic RGB methods as NIR.
        if (!realNirAvailable) {
            for (String method : methods) {
                String lower =
                        method.toLowerCase(
                                Locale.US
                        );

                boolean hasNirName =
                        lower.contains("nir")
                                || lower.contains("infrared")
                                || lower.contains("nearinfrared");

                boolean hasFrameIntent =
                        lower.contains("frame")
                                || lower.contains("image")
                                || lower.contains("liveview")
                                || lower.contains("stream")
                                || lower.contains("picture")
                                || lower.contains("data");

                boolean isFrameCommand =
                        lower.startsWith("get")
                                || lower.startsWith("start")
                                || lower.startsWith("act");

                if (hasNirName
                        && hasFrameIntent
                        && isFrameCommand) {
                    nirApiMethod = method;
                    realNirAvailable = true;
                    break;
                }
            }
        }

        return realNirAvailable;
    }

    private JSONObject callApiAt(
            Network network,
            CameraEndpoint endpoint,
            String method,
            JSONArray params,
            int connectTimeoutMs,
            int readTimeoutMs
    ) throws Exception {

        if (network == null) {

            throw new IllegalStateException(
                    "NO ACTIVE CAMERA NETWORK"
            );
        }

        if (
                endpoint == null
                        || endpoint.apiUrl == null
                        || endpoint.apiUrl.isEmpty()
        ) {

            throw new IllegalStateException(
                    "NO CAMERA API ENDPOINT"
            );
        }

        JSONObject request =
                new JSONObject();

        request.put(
                "method",
                method
        );

        request.put(
                "params",
                params == null
                        ? new JSONArray()
                        : params
        );

        request.put(
                "id",
                System.currentTimeMillis()
                        & 0x7fffffff
        );

        request.put(
                "version",
                "1.0"
        );

        URL url =
                new URL(
                        endpoint.apiUrl
                );

        URLConnection rawConnection =
                network.openConnection(
                        url
                );

        if (!(rawConnection
                instanceof HttpURLConnection)) {

            throw new IllegalStateException(
                    "CAMERA API IS NOT HTTP"
            );
        }

        HttpURLConnection http =
                (HttpURLConnection)
                        rawConnection;

        try {

            http.setRequestMethod(
                    "POST"
            );

            http.setDoOutput(
                    true
            );

            http.setUseCaches(
                    false
            );

            http.setConnectTimeout(
                    connectTimeoutMs
            );

            http.setReadTimeout(
                    readTimeoutMs
            );

            http.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
            );

            http.setRequestProperty(
                    "Accept",
                    "application/json, text/plain, */*"
            );

            byte[] body =
                    request.toString()
                            .getBytes(
                                    StandardCharsets.UTF_8
                            );

            try (
                    OutputStream output =
                            http.getOutputStream()
            ) {

                output.write(
                        body
                );

                output.flush();
            }

            int responseCode =
                    http.getResponseCode();

            InputStream input;

            if (
                    responseCode >= 200
                            && responseCode < 400
            ) {

                input =
                        http.getInputStream();

            } else {

                input =
                        http.getErrorStream();
            }

            String text =
                    readStream(
                            input
                    );

            if (
                    text == null
                            || text.trim().isEmpty()
            ) {

                throw new IllegalStateException(
                        "HTTP "
                                + responseCode
                                + " WITH EMPTY CAMERA RESPONSE"
                );
            }

            try {

                return new JSONObject(
                        text
                );

            } catch (Exception jsonError) {

                throw new IllegalStateException(
                        "CAMERA RETURNED NON-JSON: "
                                + truncate(
                                text,
                                180
                        )
                );
            }

        } finally {

            http.disconnect();
        }
    }

    private String readText(
            Network network,
            URL url,
            int timeoutMs
    ) {

        HttpURLConnection connection =
                null;

        try {

            URLConnection raw =
                    network.openConnection(
                            url
                    );

            if (!(raw
                    instanceof HttpURLConnection)) {

                return null;
            }

            connection =
                    (HttpURLConnection)
                            raw;

            connection.setRequestMethod(
                    "GET"
            );

            connection.setUseCaches(
                    false
            );

            connection.setConnectTimeout(
                    timeoutMs
            );

            connection.setReadTimeout(
                    timeoutMs
            );

            connection.setRequestProperty(
                    "Accept",
                    "application/xml, text/xml, text/plain, */*"
            );

            int code =
                    connection.getResponseCode();

            InputStream input =
                    code >= 200
                            && code < 400
                            ? connection.getInputStream()
                            : connection.getErrorStream();

            if (input == null) {
                return null;
            }

            return readStream(
                    input
            );

        } catch (Exception e) {

            return null;

        } finally {

            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readStream(
            InputStream input
    ) throws Exception {

        if (input == null) {
            return null;
        }

        try (
                InputStream in =
                        new BufferedInputStream(
                                input
                        )
        ) {

            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();

            byte[] buffer =
                    new byte[8192];

            int count;

            while (
                    (count =
                            in.read(buffer))
                            >= 0
            ) {

                if (count == 0) {
                    continue;
                }

                output.write(
                        buffer,
                        0,
                        count
                );

                if (
                        output.size()
                                > 4 * 1024 * 1024
                ) {

                    throw new IllegalStateException(
                            "RESPONSE TOO LARGE"
                    );
                }
            }

            return output.toString(
                    StandardCharsets.UTF_8.name()
            );
        }
    }

    private void startStreaming(
            String url,
            String streamBand
    ) {

        stopStreaming();

        /*
         * Starting a stream is a new preview lifetime. Any frame processor
         * still finishing work from the previous stream must self-invalidate.
         */
        previewGeneration.incrementAndGet();
        previewProcessingSequence.set(0L);

        isStreaming.set(true);
        firstFrameSent = false;
        lastNormalFrameEventAt = 0L;
        lastNdviFrameEventAt = 0L;
        lastQuadEventAt = 0L;
        streamingBand = "COMPOSITE";

        updateSystemStatus("LIVE VIEW ACTIVE", true);
        sendEvent("liveviewActive", true);
        log("INFO", "Dual-optical Live View stream started");

        executor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                URL streamUrl = new URL(url);
                Network network = currentNetwork;

                if (network == null) {
                    throw new IllegalStateException("NO CAMERA NETWORK FOR LIVE VIEW");
                }

                URLConnection raw = network.openConnection(streamUrl);
                if (!(raw instanceof HttpURLConnection)) {
                    throw new IllegalStateException("LIVE VIEW IS NOT HTTP");
                }

                connection = (HttpURLConnection) raw;
                liveviewConnection = connection;
                connection.setConnectTimeout(LIVEVIEW_CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(0);
                connection.setUseCaches(false);
                connection.setRequestProperty(
                        "Accept",
                        "image/jpeg, multipart/x-mixed-replace, */*"
                );

                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 400) {
                    throw new IllegalStateException("LIVE VIEW HTTP " + responseCode);
                }

                try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                    ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];

                    while (isStreaming.get()) {
                        int count = input.read(buffer);
                        if (count < 0) break;
                        if (count == 0) continue;

                        accumulator.write(buffer, 0, count);
                        extractJpegFrames(accumulator, "COMPOSITE");

                        if (accumulator.size() > 5 * 1024 * 1024) {
                            trimStreamBuffer(accumulator);
                        }
                    }
                }

                if (isStreaming.get()) {
                    isStreaming.set(false);
                    updateSystemStatus("LIVE VIEW DISCONNECTED", false);
                    sendEvent("streamLost", "LIVE VIEW DISCONNECTED");
                }

            } catch (Exception e) {
                if (isStreaming.get()) {
                    isStreaming.set(false);
                    Log.e(TAG, "Live View stream failed", e);
                    updateSystemStatus("LIVE VIEW DISCONNECTED", false);
                    sendEvent(
                            "streamLost",
                            "LIVE VIEW DISCONNECTED: " + safeMessage(e)
                    );
                    log("ERROR", "Live View failed: " + safeMessage(e));
                }

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                if (liveviewConnection == connection) {
                    liveviewConnection = null;
                }
            }
        });
    }

    private void extractJpegFrames(
            ByteArrayOutputStream accumulator,
            String streamBand
    ) {

        while (isStreaming.get()) {
            byte[] data = accumulator.toByteArray();

            int start = findSeq(
                    data,
                    new byte[]{(byte) 0xFF, (byte) 0xD8},
                    0
            );

            if (start < 0) {
                if (data.length > 65536) {
                    accumulator.reset();
                    accumulator.write(
                            data,
                            data.length - 65536,
                            65536
                    );
                }
                return;
            }

            int end = findSeq(
                    data,
                    new byte[]{(byte) 0xFF, (byte) 0xD9},
                    start + 2
            );

            if (end < 0) {
                if (start > 0) {
                    accumulator.reset();
                    accumulator.write(data, start, data.length - start);
                }
                return;
            }

            int jpegEnd = end + 2;
            byte[] jpeg = new byte[jpegEnd - start];
            System.arraycopy(data, start, jpeg, 0, jpeg.length);

            queueCompositeFrame(jpeg);

            byte[] remaining = new byte[data.length - jpegEnd];
            System.arraycopy(data, jpegEnd, remaining, 0, remaining.length);
            accumulator.reset();
            accumulator.write(remaining, 0, remaining.length);
        }
    }

    private String activeStreamBand() {

        return streamingBand == null
                ? "RGB"
                : streamingBand;
    }

    private void activateNirSource(
            Network network,
            CameraEndpoint endpoint
    ) {

        String method = nirApiMethod;

        if (
                network == null
                        || endpoint == null
                        || method == null
                        || method.isEmpty()
        ) {
            return;
        }

        stopStreaming();
        stopNirPolling();
        nirStreamUrl = null;

        try {

            JSONObject response =
                    callNirApiBestEffort(
                            network,
                            endpoint,
                            method
                    );

            if (response == null
                    || hasApiError(response)) {

                sendEvent(
                        "engineWarning",
                        "NIR API CALL FAILED: "
                                + apiErrorDescription(response)
                );
                revertToRgbAfterNirFailure();
                return;
            }

            String url =
                    findUrlInJson(
                            response
                    );

            if (url != null && !url.isEmpty()) {

                nirStreamUrl =
                        normalizeStreamUrl(
                                url,
                                endpoint
                        );

                if (looksLikeContinuousStream(nirStreamUrl)) {

                    startStreaming(
                            nirStreamUrl,
                            "NIR"
                    );
                    return;
                }

                byte[] frame =
                        downloadBytes(
                                network,
                                nirStreamUrl,
                                12000
                        );

                if (frame != null && frame.length > 0) {
                    emitSpectralFrame(
                            frame,
                            "NIR",
                            "NIR_CAMERA_API"
                    );

                    if (isGetStyleNirMethod(method)) {
                        startNirPolling(
                                network,
                                endpoint,
                                method
                        );
                    }
                    return;
                }
            }

            // Some custom cameras expose a one-shot image API rather than a
            // direct URL. Try a bounded poll so a changing frame source can
            // still be consumed without blocking the main camera thread.
            if (isGetStyleNirMethod(method)) {
                startNirPolling(
                        network,
                        endpoint,
                        method
                );
                return;
            }

            sendEvent(
                    "engineWarning",
                    "NIR API REACHED BUT DID NOT RETURN A FRAME URL"
            );
            revertToRgbAfterNirFailure();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "NIR activation failed",
                    e
            );

            sendEvent(
                    "engineWarning",
                    "NIR ACTIVATION FAILED: "
                            + safeMessage(e)
            );
            revertToRgbAfterNirFailure();
        }
    }

    private JSONObject callNirApiBestEffort(
            Network network,
            CameraEndpoint endpoint,
            String method
    ) throws Exception {

        JSONObject response =
                callApiAt(
                        network,
                        endpoint,
                        method,
                        new JSONArray(),
                        API_CONNECT_TIMEOUT_MS,
                        API_READ_TIMEOUT_MS
                );

        if (!hasApiError(response)) {
            return response;
        }

        String lower =
                apiErrorDescription(response)
                        .toLowerCase(
                                Locale.US
                        );

        // A number of camera stream methods accept a preferred size. Retry
        // only when the first no-argument request was rejected as an argument
        // problem; this keeps normal API errors intact.
        if (lower.contains("argument")
                || lower.contains("param")
                || lower.contains("size")) {

            JSONArray params =
                    new JSONArray();

            params.put("L");

            return callApiAt(
                    network,
                    endpoint,
                    method,
                    params,
                    API_CONNECT_TIMEOUT_MS,
                    API_READ_TIMEOUT_MS
            );
        }

        return response;
    }

    private boolean isGetStyleNirMethod(
            String method
    ) {

        if (method == null) {
            return false;
        }

        String lower =
                method.toLowerCase(
                        Locale.US
                );

        return lower.startsWith("get")
                || lower.contains("frame")
                || lower.contains("image");
    }

    private boolean looksLikeContinuousStream(
            String url
    ) {

        if (url == null) {
            return false;
        }

        String lower =
                url.toLowerCase(
                        Locale.US
                );

        return lower.contains("liveview")
                || lower.contains("liveviewstream")
                || lower.contains("mjpeg")
                || lower.contains("multipart")
                || lower.contains("stream");
    }

    private void startNirPolling(
            Network network,
            CameraEndpoint endpoint,
            String method
    ) {

        if (!isNirPolling.compareAndSet(
                false,
                true
        )) {
            return;
        }

        executor.execute(
                () -> {

                    try {

                        while (
                                isNirPolling.get()
                                        && "NIR".equals(activeSpectralBand)
                        ) {

                            JSONObject response =
                                    callNirApiBestEffort(
                                            network,
                                            endpoint,
                                            method
                                    );

                            if (response != null
                                    && !hasApiError(response)) {

                                String url =
                                        findFirstImageUrl(
                                                response
                                        );

                                if (url == null || url.isEmpty()) {
                                    url = findUrlInJson(response);
                                }

                                if (url != null && !url.isEmpty()) {

                                    String normalized =
                                            normalizeStreamUrl(
                                                    url,
                                                    endpoint
                                            );

                                    if (!looksLikeContinuousStream(normalized)) {

                                        byte[] frame =
                                                downloadBytes(
                                                        network,
                                                        normalized,
                                                        10000
                                                );

                                        if (frame != null && frame.length > 0) {
                                            emitSpectralFrame(
                                                    frame,
                                                    "NIR",
                                                    "NIR_CAMERA_API"
                                            );
                                        }
                                    } else {
                                        // If polling suddenly returns a real
                                        // MJPEG endpoint, hand it to the same
                                        // streaming parser used by RGB.
                                        stopNirPolling();
                                        nirStreamUrl = normalized;
                                        startStreaming(
                                                normalized,
                                                "NIR"
                                        );
                                        return;
                                    }
                                }
                            }

                            sleepQuietly(220L);
                        }

                    } catch (Exception e) {

                        if (isNirPolling.get()) {
                            Log.e(
                                    TAG,
                                    "NIR polling failed",
                                    e
                            );
                            sendEvent(
                                    "engineWarning",
                                    "NIR STREAM POLLING FAILED: "
                                            + safeMessage(e)
                            );
                        }

                    } finally {

                        isNirPolling.set(false);
                    }
                }
        );
    }

    private void queueCompositeFrame(byte[] compositeJpeg) {
        if (compositeJpeg == null || compositeJpeg.length == 0) {
            return;
        }

        /*
         * Latest-frame-only:
         * a newer network frame replaces an older queued frame. The stream
         * reader never waits for bitmap/NDVI processing.
         */
        pendingCompositeFrame.set(compositeJpeg);

        if (!frameProcessorRunning.compareAndSet(false, true)) {
            return;
        }

        final long workerPreviewGeneration =
                previewGeneration.get();

        executor.execute(() -> {
            try {
                while (isStreaming.get()
                        && workerPreviewGeneration == previewGeneration.get()) {

                    byte[] frame =
                            pendingCompositeFrame.getAndSet(null);

                    if (frame == null) {
                        break;
                    }

                    processAndEmitCompositeFrame(frame);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Live-view frame processor failed", t);
            } finally {
                frameProcessorRunning.set(false);

                /*
                 * A frame can arrive between getAndSet(null) and the CAS.
                 * Restart only for the same stream/mode generation. A mode
                 * change intentionally lets this worker die so stale work
                 * cannot cross the mode boundary.
                 */
                if (pendingCompositeFrame.get() != null
                        && isStreaming.get()
                        && workerPreviewGeneration == previewGeneration.get()
                        && frameProcessorRunning.compareAndSet(false, true)) {

                    executor.execute(() -> {
                        try {
                            while (isStreaming.get()
                                    && workerPreviewGeneration == previewGeneration.get()) {

                                byte[] frame =
                                        pendingCompositeFrame.getAndSet(null);

                                if (frame == null) {
                                    break;
                                }

                                processAndEmitCompositeFrame(frame);
                            }
                        } catch (Throwable t) {
                            Log.e(
                                    TAG,
                                    "Live-view frame processor restart failed",
                                    t
                            );
                        } finally {
                            frameProcessorRunning.set(false);
                        }
                    });
                }
            }
        });
    }

    private void processAndEmitCompositeFrame(byte[] compositeJpeg) {

        if (compositeJpeg == null || compositeJpeg.length == 0) {
            return;
        }

        /*
         * Snapshot the current preview generation at the start of the job.
         * The job may never publish a frame if the user changes mode while
         * decode/calibration/rendering is in progress.
         */
        final long framePreviewGeneration =
                previewGeneration.get();

        /*
         * Apply frame cadence before BitmapFactory decode for the normal
         * processed preview path. This is where PERFORMANCE mode actually
         * reduces CPU/memory pressure rather than merely dropping completed
         * results. RAW preview and NDVI keep their own cadence/ownership.
         */
        if (!ndviEnabled
                && !CAPTURE_MODE_RAW.equals(captureMode)) {

            final long processingSequence =
                    previewProcessingSequence.incrementAndGet();

            final int frameCadence =
                    Math.max(
                            1,
                            processingEveryNFrames
                    );

            if (frameCadence > 1
                    && ((processingSequence - 1L)
                    % frameCadence) != 0L) {
                return;
            }
        }

        BitmapFactory.Options options =
                new BitmapFactory.Options();
        options.inPreferredConfig =
                Bitmap.Config.ARGB_8888;

        Bitmap source =
                BitmapFactory.decodeByteArray(
                        compositeJpeg,
                        0,
                        compositeJpeg.length,
                        options
                );

        if (source == null) {
            log(
                    "WARN",
                    "Composite frame decode failed"
            );
            return;
        }

        try {
            if (source.getWidth() > MAX_PROCESSING_DIMENSION
                    || source.getHeight() > MAX_PROCESSING_DIMENSION) {
                log(
                        "WARN",
                        "Composite frame exceeds processing dimension"
                );
                return;
            }

            /*
             * A mode can change while BitmapFactory is decoding. Do not
             * continue expensive work for an already-stale frame.
             */
            if (framePreviewGeneration != previewGeneration.get()) {
                return;
            }

            ensureDualOpticalCalibration(source);
            lastCompositeFrameBytes = compositeJpeg;

            /*
             * ================================================================
             * NDVI IS AN EXCLUSIVE PREVIEW MODE.
             *
             * Once NDVI is enabled, this branch owns the entire preview
             * pipeline. Even when the next NDVI frame is not due yet, we
             * RETURN instead of falling through to RGB/R/G/B/NIR processing.
             *
             * This is the primary anti-flicker rule.
             * ================================================================
             */
            if (ndviEnabled) {

                if (framePreviewGeneration != previewGeneration.get()
                        || !ndviEnabled) {
                    return;
                }

                final long now =
                        System.currentTimeMillis();

                final boolean previewDue =
                        now - lastNdviPreviewAt
                                >= NDVI_PREVIEW_INTERVAL_MS;

                if (!previewDue) {
                    /*
                     * Hold the last visible NDVI frame. Missing cadence is
                     * never permission to publish RGB.
                     */
                    emitNdviIfDue(source);
                    return;
                }

                updateGpuNdviCalibrationIfDue(source);

                if (framePreviewGeneration != previewGeneration.get()
                        || !ndviEnabled) {
                    return;
                }

                boolean gpuRendered = false;

                /*
                 * Renderer policy:
                 *
                 * UNKNOWN -> try GPU when a valid Surface exists.
                 * GPU     -> stay GPU for this NDVI session.
                 * CPU     -> stay CPU for this NDVI session.
                 */
                if (ndviRendererMode != NDVI_RENDERER_CPU) {
                    gpuRendered =
                            renderGpuNdvi(source);

                    if (gpuRendered) {
                        ndviRendererMode =
                                NDVI_RENDERER_GPU;

                        if (framePreviewGeneration
                                != previewGeneration.get()
                                || !ndviEnabled) {
                            return;
                        }

                        lastNdviPreviewAt =
                                System.currentTimeMillis();

                        emitGpuNdviFrame(
                                rgbCropRect == null
                                        ? 0
                                        : rgbCropRect.width(),
                                rgbCropRect == null
                                        ? 0
                                        : rgbCropRect.height(),
                                framePreviewGeneration
                        );

                        emitNdviIfDue(source);

                        /*
                         * Never execute normal spectral processing after a
                         * successful NDVI commit.
                         */
                        return;
                    }
                }

                /*
                 * A real GPU renderer failure marks the session as CPU inside
                 * renderGpuNdvi(). If the GPU surface is merely unavailable,
                 * the renderer mode remains UNKNOWN and CPU is used only as a
                 * temporary safety path. Crucially, neither case falls through
                 * to RGB.
                 */
                if (ndviRendererMode
                        == NDVI_RENDERER_GPU) {
                    /*
                     * GPU was selected for the session but this frame did not
                     * commit. Hold the last NDVI frame rather than changing
                     * renderer or showing RGB.
                     */
                    emitNdviIfDue(source);
                    return;
                }

                byte[] ndviPreview =
                        buildNdviPreviewJpeg(source);

                if (ndviPreview != null
                        && ndviPreview.length > 0
                        && framePreviewGeneration
                                == previewGeneration.get()
                        && ndviEnabled) {

                    /*
                     * CPU fallback is a pure NDVI image: RED vs NIR only.
                     * Keep it entirely inside the NDVI mode boundary.
                     */
                    /*
                     * Keep UNKNOWN when the GPU surface was simply unavailable.
                     * The next frame may acquire a valid surface and use GPU.
                     * A real GPU failure changes the mode to CPU inside
                     * renderGpuNdvi(), making CPU sticky for that NDVI session.
                     */
                    lastNdviPreviewAt =
                            System.currentTimeMillis();

                    lastFrameBytes =
                            ndviPreview;

                    emitPreviewFrame(
                            ndviPreview,
                            "NDVI",
                            "NDVI_RED_VS_NIR_CPU_FALLBACK",
                            true,
                            "NDVI",
                            rgbCropRect == null
                                    ? 0
                                    : rgbCropRect.width(),
                            rgbCropRect == null
                                    ? 0
                                    : rgbCropRect.height(),
                            rgbCropRect,
                            framePreviewGeneration
                    );

                    emitNdviIfDue(source);
                }

                /*
                 * ABSOLUTE RULE:
                 * NDVI mode never falls through to normal spectral preview.
                 */
                return;
            }

            /*
             * NDVI is off here. Any frame that started in an older generation
             * is stale and must be discarded.
             */
            if (framePreviewGeneration
                    != previewGeneration.get()) {
                return;
            }

            /*
             * RAW preview is still a capture/preview mode distinct from the
             * spectral path. It can only be published while NDVI is off.
             */
            if (CAPTURE_MODE_RAW.equals(captureMode)) {

                if (framePreviewGeneration
                        != previewGeneration.get()
                        || ndviEnabled) {
                    return;
                }

                byte[] previewJpeg =
                        buildScaledPreviewJpeg(
                                source,
                                previewScale
                        );

                if (previewJpeg == null
                        || previewJpeg.length == 0) {
                    return;
                }

                lastFrameBytes =
                        previewJpeg;

                emitPreviewFrame(
                        previewJpeg,
                        activeSpectralBand == null
                                ? "RGB"
                                : activeSpectralBand,
                        "FULL_COMPOSITE",
                        false,
                        "RAW",
                        scaledDimension(
                                source.getWidth(),
                                previewScale
                        ),
                        scaledDimension(
                                source.getHeight(),
                                previewScale
                        ),
                        null,
                        framePreviewGeneration
                );
                return;
            }

            final String band =
                    activeSpectralBand == null
                            ? "RGB"
                            : activeSpectralBand;

            final long generation =
                    spectralGeneration.get();

            byte[] processed =
                    processCompositeForBand(
                            source,
                            band
                    );

            if (processed == null
                    || processed.length == 0) {
                return;
            }

            /*
             * Both the spectral generation and preview generation must still
             * match before a normal processed frame can own the screen.
             */
            if (generation != spectralGeneration.get()
                    || framePreviewGeneration
                            != previewGeneration.get()
                    || ndviEnabled
                    || !band.equals(activeSpectralBand)) {
                return;
            }

            lastFrameBytes =
                    processed;

            emitProcessedFrame(
                    processed,
                    band,
                    "NIR".equals(band)
                            ? "NIR_RIGHT_OPTICAL_ROI"
                            : "RGB_LEFT_OPTICAL_ROI",
                    true,
                    framePreviewGeneration
            );

        } finally {
            source.recycle();
        }
    }

    /**
     * Calibrate the dual circular optical fields once per source resolution.
     *
     * The camera frame is a single composite image containing two circular
     * fields. We estimate the visible circle bounds from the near-black outer
     * mask, then compute the LARGEST safe axis-aligned square that fits inside
     * the circle/ellipse and also remains inside the source bitmap.
     *
     * This is the key rule that prevents black/vignetted corners in every
     * RGB/R/G/B/NIR result.
     */

    /**
     * Builds a practical false-colour NDVI preview from the two optical ROIs.
     *
     * The RGB field is the spatial coordinate system only. Each output pixel
     * uses the RED channel from the RGB ROI and the registered NIR intensity
     * from the right optical ROI. The resulting relative/digital NDVI is
     * mapped RED -> YELLOW -> GREEN.
     *
     * The output is PURE NDVI false colour. The RGB pixels are never blended
     * into the visualization.
     */
    /**
     * Live NDVI preview for the unified crop.
     *
     * RGB is the master coordinate system.  The NIR optical ROI is sampled
     * through the same affine registration for every output pixel, so the
     * displayed NDVI is a single image rather than a left/right composite.
     *
     * The numeric result remains relative/digital NDVI because this source
     * does not provide radiometrically calibrated reflectance.  A robust NIR
     * gain is estimated from the current scene to prevent trivial exposure
     * imbalance from collapsing the visualization to an almost all-negative
     * map.  The gain is smoothed over time to avoid frame-to-frame flicker.
     */
    private byte[] buildNdviPreviewJpeg(Bitmap source) {
        return buildNdviJpeg(source, true);
    }

    private byte[] buildNdviJpeg(
            Bitmap source,
            boolean preview
    ) {
        Rect rgbRect = unifiedRgbRect();
        if (!isUsableCrop(
                rgbRect,
                source.getWidth(),
                source.getHeight()
        )
                || !isUsableCrop(
                nirCropRect,
                source.getWidth(),
                source.getHeight()
        )) {
            return null;
        }

        final int width = rgbRect.width();
        final int height = rgbRect.height();
        if (width <= 1 || height <= 1) {
            return null;
        }

        final int pixelCount = width * height;
        final int maxPixels = preview
                ? NDVI_MAX_PREVIEW_PIXELS
                : Integer.MAX_VALUE;
        final int step = pixelCount > maxPixels
                ? Math.max(
                        2,
                        (int) Math.ceil(
                                Math.sqrt(
                                        (double) pixelCount / (double) maxPixels
                                )
                        )
                )
                : 1;

        final int sampledWidth =
                (width + step - 1) / step;
        final int sampledHeight =
                (height + step - 1) / step;

        final int[] rgbPixels =
                new int[pixelCount];

        final int nirWidth =
                nirCropRect.width();
        final int nirHeight =
                nirCropRect.height();

        final int[] nirPixels =
                new int[nirWidth * nirHeight];

        source.getPixels(
                rgbPixels,
                0,
                width,
                rgbRect.left,
                rgbRect.top,
                width,
                height
        );

        source.getPixels(
                nirPixels,
                0,
                nirWidth,
                nirCropRect.left,
                nirCropRect.top,
                nirWidth,
                nirHeight
        );

        final int[] redHistogram =
                new int[256];
        final int[] nirHistogram =
                new int[256];

        int histogramCount = 0;

        /*
         * First pass: collect robust signal histograms from the exact same
         * registration mapping that is used for the output.
         */
        for (int sy = 0; sy < sampledHeight; sy++) {
            final int y =
                    Math.min(
                            height - 1,
                            sy * step
                    );

            for (int sx = 0; sx < sampledWidth; sx++) {
                final int x =
                        Math.min(
                                width - 1,
                                sx * step
                        );

                final long mapped =
                        mapUnifiedPixelToNir(
                                x,
                                y,
                                width,
                                height,
                                nirWidth,
                                nirHeight
                        );

                if (mapped < 0L) {
                    continue;
                }

                final int sourceX =
                        (int) (mapped >> 32);
                final int sourceY =
                        (int) mapped;

                final int base =
                        rgbPixels[y * width + x];

                final int nirColor =
                        nirPixels[sourceY * nirWidth + sourceX];

                final int red =
                        android.graphics.Color.red(base);

                final int nirValue =
                        pixelLuma(nirColor);

                if (red < NDVI_MIN_VALID_SIGNAL
                        || nirValue < NDVI_MIN_VALID_SIGNAL) {
                    continue;
                }

                redHistogram[red]++;
                nirHistogram[nirValue]++;
                histogramCount++;
            }
        }

        if (histogramCount < 64) {
            return null;
        }

        final int redReference =
                histogramPercentile(
                        redHistogram,
                        histogramCount,
                        NDVI_BALANCE_PERCENTILE
                );

        final int nirReference =
                histogramPercentile(
                        nirHistogram,
                        histogramCount,
                        NDVI_BALANCE_PERCENTILE
                );

        final float targetGain =
                nirReference <= NDVI_MIN_VALID_SIGNAL
                        ? 1.0f
                        : clampFloat(
                                (float) redReference
                                        / (float) nirReference,
                                NDVI_NIR_GAIN_MIN,
                                NDVI_NIR_GAIN_MAX
                        );

        final float oldGain =
                lastNdviNirGain <= 0f
                        ? 1.0f
                        : lastNdviNirGain;

        final float gain =
                oldGain
                        + (targetGain - oldGain)
                        * NDVI_GAIN_SMOOTHING;

        lastNdviNirGain = gain;

        final int[] outputPixels =
                new int[sampledWidth * sampledHeight];

        long sumScaledNdvi = 0L;
        int valid = 0;

        for (int sy = 0; sy < sampledHeight; sy++) {
            final int y =
                    Math.min(
                            height - 1,
                            sy * step
                    );

            for (int sx = 0; sx < sampledWidth; sx++) {
                final int x =
                        Math.min(
                                width - 1,
                                sx * step
                        );

                final int outputIndex =
                        sy * sampledWidth + sx;

                final int base =
                        rgbPixels[y * width + x];

                final long mapped =
                        mapUnifiedPixelToNir(
                                x,
                                y,
                                width,
                                height,
                                nirWidth,
                                nirHeight
                        );

                if (mapped < 0L) {
                    outputPixels[outputIndex] = android.graphics.Color.BLACK;
                    continue;
                }

                final int sourceX =
                        (int) (mapped >> 32);

                final int sourceY =
                        (int) mapped;

                final int nirColor =
                        nirPixels[
                                sourceY * nirWidth + sourceX
                        ];

                final int red =
                        android.graphics.Color.red(base);

                final int nirRaw =
                        pixelLuma(nirColor);

                if (red < NDVI_MIN_VALID_SIGNAL
                        || nirRaw < NDVI_MIN_VALID_SIGNAL) {
                    outputPixels[outputIndex] = android.graphics.Color.BLACK;
                    continue;
                }

                final float nir =
                        nirRaw * gain;

                final float denominator =
                        nir + red;

                if (denominator < 12.0f) {
                    outputPixels[outputIndex] = android.graphics.Color.BLACK;
                    continue;
                }

                final float ndvi =
                        (nir - red) / denominator;

                if (Float.isNaN(ndvi)
                        || Float.isInfinite(ndvi)
                        || ndvi < -1.0f
                        || ndvi > 1.0f) {
                    outputPixels[outputIndex] = android.graphics.Color.BLACK;
                    continue;
                }

                final int lutIndex =
                        clampInt(
                                Math.round(
                                        ((ndvi + 1.0f) * 0.5f)
                                                * (NDVI_LUT_SIZE - 1)
                                ),
                                0,
                                NDVI_LUT_SIZE - 1
                        );

                final int overlay =
                        NDVI_COLOR_LUT[lutIndex];

                // PURE NDVI output: never show the RGB base under the map.
                outputPixels[outputIndex] = overlay;

                sumScaledNdvi +=
                        Math.round(
                                ndvi * 100000.0f
                        );

                valid++;
            }
        }

        if (valid < 64) {
            return null;
        }

        lastNdviValue =
                (double) sumScaledNdvi
                        / (double) valid
                        / 100000.0;

        lastNdviValidPixels =
                valid;
        lastNdviComputedAt =
                System.currentTimeMillis();

        Bitmap output =
                Bitmap.createBitmap(
                        outputPixels,
                        0,
                        sampledWidth,
                        sampledWidth,
                        sampledHeight,
                        Bitmap.Config.ARGB_8888
                );

        if (output == null) {
            return null;
        }

        try {
            return bitmapToJpeg(
                    output,
                    preview
                            ? NDVI_PREVIEW_JPEG_QUALITY
                            : NDVI_CAPTURE_JPEG_QUALITY
            );
        } finally {
            output.recycle();
        }
    }

    private void updateGpuNdviCalibrationIfDue(Bitmap source) {
        long now = System.currentTimeMillis();
        if (now - lastNdviCalibrationAt < GPU_NDVI_CALIBRATION_INTERVAL_MS) {
            return;
        }

        Rect rgb = unifiedRgbRect();
        Rect nirRect = nirCropRect;
        if (!isUsableCrop(rgb, source.getWidth(), source.getHeight())
                || !isUsableCrop(nirRect, source.getWidth(), source.getHeight())) {
            return;
        }

        final int grid = GPU_NDVI_GAIN_SAMPLE_GRID;
        final int countMax = grid * grid;
        final int[] redSamples = new int[countMax];
        final int[] nirSamples = new int[countMax];
        int count = 0;
        double ndviSum = 0.0;
        int valid = 0;

        for (int gy = 0; gy < grid; gy++) {
            final float fy = grid <= 1 ? 0.5f : (float) gy / (float) (grid - 1);
            final int y = rgb.top + Math.round(fy * Math.max(0, rgb.height() - 1));

            for (int gx = 0; gx < grid; gx++) {
                final float fx = grid <= 1 ? 0.5f : (float) gx / (float) (grid - 1);
                final int x = rgb.left + Math.round(fx * Math.max(0, rgb.width() - 1));

                final long mapped = mapUnifiedPixelToNir(
                        x - rgb.left,
                        y - rgb.top,
                        rgb.width(),
                        rgb.height(),
                        nirRect.width(),
                        nirRect.height()
                );

                if (mapped < 0L) continue;

                final int sourceX = (int) (mapped >> 32);
                final int sourceY = (int) mapped;

                final int rgbColor = source.getPixel(x, y);
                final int nirColor = source.getPixel(
                        nirRect.left + sourceX,
                        nirRect.top + sourceY
                );

                final int red = android.graphics.Color.red(rgbColor);
                final int nirValue = pixelLuma(nirColor);

                if (red < NDVI_MIN_VALID_SIGNAL || nirValue < NDVI_MIN_VALID_SIGNAL) {
                    continue;
                }

                if (count < countMax) {
                    redSamples[count] = red;
                    nirSamples[count] = nirValue;
                    count++;
                }
            }
        }

        if (count < 64) {
            lastNdviCalibrationAt = now;
            return;
        }

        Arrays.sort(redSamples, 0, count);
        Arrays.sort(nirSamples, 0, count);

        final int percentileIndex =
                clampInt(
                        (int) Math.round(
                                (count - 1) * (NDVI_BALANCE_PERCENTILE / 100.0)
                        ),
                        0,
                        count - 1
                );

        final int redReference = redSamples[percentileIndex];
        final int nirReference = nirSamples[percentileIndex];

        final float targetGain =
                nirReference <= NDVI_MIN_VALID_SIGNAL
                        ? 1.0f
                        : clampFloat(
                                (float) redReference / (float) nirReference,
                                NDVI_NIR_GAIN_MIN,
                                NDVI_NIR_GAIN_MAX
                        );

        final float oldGain = lastNdviNirGain <= 0.0f
                ? 1.0f
                : lastNdviNirGain;

        final float gain =
                oldGain
                        + (targetGain - oldGain) * NDVI_GAIN_SMOOTHING;

        lastNdviNirGain = gain;

        // Compute the scalar value at the same low-rate cadence. The visual
        // field itself remains entirely GPU-generated.
        for (int i = 0; i < count; i++) {
            final float red = redSamples[i] / 255.0f;
            final float nir = (nirSamples[i] / 255.0f) * gain;
            final float denom = nir + red;
            if (red <= GPU_NDVI_MIN_SIGNAL || nir <= GPU_NDVI_MIN_SIGNAL || denom <= 0.0f) {
                continue;
            }
            final float ndvi = clampFloat(
                    (nir - red) / denom,
                    -1.0f,
                    1.0f
            );
            if (Float.isFinite(ndvi)) {
                ndviSum += ndvi;
                valid++;
            }
        }

        if (valid > 0) {
            lastNdviValue = ndviSum / (double) valid;
            lastNdviValidPixels = valid;
            lastNdviComputedAt = now;
        }

        lastNdviCalibrationAt = now;
    }

    private boolean renderGpuNdvi(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return false;
        }

        synchronized (gpuNdviLock) {
            if (gpuNdviSurface == null || !gpuNdviSurface.isValid()) {
                return false;
            }

            try {
                if (gpuNdviRenderer == null) {
                    gpuNdviRenderer = new GpuNdviRenderer(
                            gpuNdviSurface,
                            GPU_NDVI_OUTPUT_WIDTH,
                            GPU_NDVI_OUTPUT_HEIGHT
                    );
                }

                Rect rgb = unifiedRgbRect();
                Rect nir = nirCropRect;
                if (!isUsableCrop(rgb, source.getWidth(), source.getHeight())
                        || !isUsableCrop(nir, source.getWidth(), source.getHeight())) {
                    return false;
                }

                gpuNdviRenderer.render(
                        source,
                        rgb,
                        nir,
                        lastNdviNirGain,
                        NIR_REGISTRATION_SCALE_X,
                        NIR_REGISTRATION_SCALE_Y,
                        NIR_REGISTRATION_SHIFT_X_PX,
                        NIR_REGISTRATION_SHIFT_Y_PX,
                        NIR_REGISTRATION_COS,
                        NIR_REGISTRATION_SIN
                );

                return true;
            } catch (Throwable t) {
                /*
                 * A genuine GPU renderer failure is a session-level decision.
                 * Release the GL objects once and stay on CPU for the rest of
                 * this NDVI session. This avoids GPU/CPU oscillation.
                 */
                Log.e(
                        TAG,
                        "GPU NDVI render failed; switching to CPU session",
                        t
                );
                ndviRendererMode =
                        NDVI_RENDERER_CPU;
                releaseGpuNdviRendererLocked();
                return false;
            }
        }
    }

    private void emitGpuNdviFrame(
            int width,
            int height,
            long expectedPreviewGeneration
    ) {
        /*
         * The GPU surface itself holds the last successful buffer. We rate
         * limit the lightweight Dart event separately from the actual GPU
         * rendering, and we never publish a mode-mismatched event.
         */
        if (!ndviEnabled
                || expectedPreviewGeneration
                        != previewGeneration.get()) {
            return;
        }

        long now =
                System.currentTimeMillis();

        if (now - lastNdviFrameEventAt
                < NDVI_EVENT_INTERVAL_MS) {
            return;
        }

        lastNdviFrameEventAt =
                now;

        HashMap<String, Object> event =
                new HashMap<>();

        event.put("bytes", null);
        event.put("band", "NDVI");
        event.put(
                "source",
                "NDVI_RED_VS_NIR_GPU"
        );
        event.put("processed", true);
        event.put(
                "previewMode",
                "NDVI"
        );
        event.put("gpuTexture", true);
        event.put("captureMode", captureMode);
        event.put("isQuadFrame", false);
        event.put("realSpectralFrame", true);
        event.put("width", GPU_NDVI_OUTPUT_WIDTH);
        event.put("height", GPU_NDVI_OUTPUT_HEIGHT);
        event.put("compositeWidth", calibratedSourceWidth);
        event.put("compositeHeight", calibratedSourceHeight);
        event.put(
                "cropX",
                rgbCropRect == null
                        ? 0
                        : rgbCropRect.left
        );
        event.put(
                "cropY",
                rgbCropRect == null
                        ? 0
                        : rgbCropRect.top
        );
        event.put("cropWidth", width);
        event.put("cropHeight", height);
        event.put(
                "unifiedSpectralCrop",
                true
        );
        event.put(
                "registrationApplied",
                true
        );
        event.put(
                "registrationModel",
                "CENTERED_AFFINE_GPU"
        );
        event.put(
                "ndviInput",
                "RED_CHANNEL_VS_NIR_INTENSITY"
        );
        event.put(
                "nirGain",
                lastNdviNirGain
        );
        event.put(
                "previewGeneration",
                expectedPreviewGeneration
        );

        sendEvent(
                "liveviewFrame",
                event
        );

        if (!firstFrameSent
                && ndviEnabled
                && expectedPreviewGeneration
                        == previewGeneration.get()) {

            firstFrameSent = true;

            sendEvent(
                    "firstLiveviewFrame",
                    true
            );

            updateSystemStatus(
                    "CAMERA READY",
                    true
            );
        }
    }

    /**
     * Maps one unified-output pixel to a source pixel in the NIR crop.
     *
     * The return value packs x/y into one long to avoid per-pixel allocations
     * and, unlike shared scratch fields, remains safe when a capture and the
     * live-view worker happen to run concurrently.
     *
     * -1L means the mapped coordinate falls outside the NIR optical crop.
     */
    private long mapUnifiedPixelToNir(
            int outputX,
            int outputY,
            int outputWidth,
            int outputHeight,
            int nirWidth,
            int nirHeight
    ) {
        final float safeScaleX =
                Math.max(
                        0.001f,
                        Math.abs(NIR_REGISTRATION_SCALE_X)
                );

        final float safeScaleY =
                Math.max(
                        0.001f,
                        Math.abs(NIR_REGISTRATION_SCALE_Y)
                );

        final float outCx =
                (outputWidth - 1) * 0.5f;

        final float outCy =
                (outputHeight - 1) * 0.5f;

        final float srcCx =
                (nirWidth - 1) * 0.5f;

        final float srcCy =
                (nirHeight - 1) * 0.5f;

        final float shiftedX =
                (
                        outputX
                                - outCx
                                - NIR_REGISTRATION_SHIFT_X_PX
                ) / safeScaleX;

        final float shiftedY =
                (
                        outputY
                                - outCy
                                - NIR_REGISTRATION_SHIFT_Y_PX
                ) / safeScaleY;

        final float sourceX =
                srcCx
                        + shiftedX * NIR_REGISTRATION_COS
                        + shiftedY * NIR_REGISTRATION_SIN;

        final float sourceY =
                srcCy
                        - shiftedX * NIR_REGISTRATION_SIN
                        + shiftedY * NIR_REGISTRATION_COS;

        if (sourceX < 0f
                || sourceY < 0f
                || sourceX > nirWidth - 1
                || sourceY > nirHeight - 1) {
            return -1L;
        }

        final int mappedX =
                clampInt(
                        Math.round(sourceX),
                        0,
                        nirWidth - 1
                );

        final int mappedY =
                clampInt(
                        Math.round(sourceY),
                        0,
                        nirHeight - 1
                );

        return (
                ((long) mappedX) << 32
        ) | (
                mappedY & 0xFFFFFFFFL
        );
    }

    private float clampFloat(
            float value,
            float min,
            float max
    ) {
        return Math.max(
                min,
                Math.min(
                        max,
                        value
                )
        );
    }

    private static int[] buildNdviColorLut() {
        int[] lut = new int[NDVI_LUT_SIZE];
        float[] hsv = new float[3];
        for (int i = 0; i < NDVI_LUT_SIZE; i++) {
            float normalized =
                    (float) i / (float) (NDVI_LUT_SIZE - 1);
            hsv[0] = normalized * 120.0f;
            hsv[1] = 0.92f;
            hsv[2] = 1.0f;
            lut[i] = android.graphics.Color.HSVToColor(hsv);
        }
        return lut;
    }

    private static int histogramPercentile(
            int[] histogram,
            int total,
            int percentile
    ) {
        if (histogram == null || histogram.length == 0 || total <= 0) {
            return 0;
        }

        int target = Math.max(
                1,
                (int) Math.ceil(total * (percentile / 100.0))
        );
        int cumulative = 0;

        for (int i = 0; i < histogram.length; i++) {
            cumulative += histogram[i];
            if (cumulative >= target) {
                return i;
            }
        }

        return histogram.length - 1;
    }

    private static int blendRgb(
            int base,
            int overlay,
            int alpha
    ) {
        final int a = clampInt(alpha, 0, 255);
        final int inv = 255 - a;

        final int r = (
                android.graphics.Color.red(base) * inv
                        + android.graphics.Color.red(overlay) * a
        ) / 255;
        final int g = (
                android.graphics.Color.green(base) * inv
                        + android.graphics.Color.green(overlay) * a
        ) / 255;
        final int b = (
                android.graphics.Color.blue(base) * inv
                        + android.graphics.Color.blue(overlay) * a
        ) / 255;

        return android.graphics.Color.rgb(r, g, b);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void emitNdviIfDue(Bitmap source) {
        if (!ndviEnabled) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastNdviEventAt < NDVI_EVENT_INTERVAL_MS) {
            return;
        }

        if (!Double.isFinite(lastNdviValue)
                || lastNdviValidPixels <= 0) {
            return;
        }

        if (lastNdviComputedAt <= lastNdviEventAt) {
            return;
        }

        lastNdviEventAt = now;

        HashMap<String, Object> data = new HashMap<>();
        data.put("value", lastNdviValue);
        data.put("min", -1.0);
        data.put("max", 1.0);
        data.put("validPixels", lastNdviValidPixels);
        data.put("metric", "RELATIVE_DIGITAL_NDVI");
        data.put("source", "UNIFIED_RGB_NIR_REGISTERED_CROP");
        data.put("normalized", true);
        data.put("registrationModel", "CENTERED_AFFINE");
        data.put("nirGain", lastNdviNirGain);

        sendEvent("ndvi", data);
    }

    private void ensureDualOpticalCalibration(Bitmap source) {

        int width = source.getWidth();
        int height = source.getHeight();

        if (width == calibratedSourceWidth
                && height == calibratedSourceHeight
                && rgbCropRect != null
                && nirCropRect != null) {
            return;
        }

        int opticalSplit = detectOpticalSplit(source);

        Rect detectedRgb = detectOpticalRoi(
                source,
                0,
                opticalSplit
        );

        Rect detectedNir = detectOpticalRoi(
                source,
                opticalSplit,
                width
        );

        if (!isUsableCrop(detectedRgb, width, height)) {
            detectedRgb = fallbackSquareCrop(
                    width,
                    height,
                    FALLBACK_RGB_LEFT,
                    FALLBACK_RGB_TOP,
                    FALLBACK_RGB_RIGHT,
                    FALLBACK_RGB_BOTTOM
            );
        }

        if (!isUsableCrop(detectedNir, width, height)) {
            detectedNir = fallbackSquareCrop(
                    width,
                    height,
                    FALLBACK_NIR_LEFT,
                    FALLBACK_NIR_TOP,
                    FALLBACK_NIR_RIGHT,
                    FALLBACK_NIR_BOTTOM
            );
        }

        rgbCropRect = detectedRgb;
        nirCropRect = detectedNir;
        calibratedSourceWidth = width;
        calibratedSourceHeight = height;

        boolean validDual =
                isUsableCrop(rgbCropRect, width, height)
                        && isUsableCrop(nirCropRect, width, height);

        boolean changed = realNirAvailable != validDual;
        realNirAvailable = validDual;

        Rect unified = unifiedRgbRect();

        log(
                "INFO",
                "Unified optical calibration: RGB="
                        + rectToString(rgbCropRect)
                        + " NIR="
                        + rectToString(nirCropRect)
                        + " UNIFIED="
                        + rectToString(unified)
                        + " REG="
                        + NIR_REGISTRATION_SCALE_X
                        + "x"
                        + NIR_REGISTRATION_SCALE_Y
                        + " rot="
                        + NIR_REGISTRATION_ROTATION_DEG
                        + " shift="
                        + NIR_REGISTRATION_SHIFT_X_PX
                        + ","
                        + NIR_REGISTRATION_SHIFT_Y_PX
        );

        if (changed) {
            emitCapabilities(Collections.emptySet());
        }
    }

    /**
     * Detect the dark optical gap between the two circular fields.
     *
     * The two circles are not necessarily separated exactly at width / 2.
     * Using the actual dark gap prevents the right circle from being treated
     * as clipped on both sides, which was the cause of oversized crops with
     * black corners.
     */
    private int detectOpticalSplit(Bitmap source) {

        final int width = source.getWidth();
        final int height = source.getHeight();
        final float threshold = opticalBlackThreshold(source);

        int yStart = Math.max(0, Math.round(height * 0.22f));
        int yEnd = Math.min(height, Math.round(height * 0.78f));

        float[] darkProfile = new float[width];
        int samples = Math.max(
                1,
                (yEnd - yStart + ROI_SCAN_STEP - 1) / ROI_SCAN_STEP
        );

        for (int x = 0; x < width; x++) {
            int dark = 0;
            for (int i = 0; i < samples; i++) {
                int y = Math.min(
                        yEnd - 1,
                        yStart + i * ROI_SCAN_STEP
                );
                if (pixelLuma(source.getPixel(x, y)) <= threshold) {
                    dark++;
                }
            }
            darkProfile[x] = (float) dark / (float) samples;
        }

        smoothProfile(darkProfile, 12);

        int searchStart = Math.max(1, Math.round(width * 0.35f));
        int searchEnd = Math.min(width - 1, Math.round(width * 0.65f));
        int minimumRun = Math.max(16, Math.round(width * 0.015f));

        int bestStart = -1;
        int bestEnd = -1;
        int runStart = -1;

        for (int x = searchStart; x <= searchEnd; x++) {
            boolean dark = darkProfile[x] >= 0.82f;

            if (dark) {
                if (runStart < 0) runStart = x;
            } else if (runStart >= 0) {
                if (x - runStart >= minimumRun) {
                    if (isBetterSplitRun(
                            runStart,
                            x - 1,
                            bestStart,
                            bestEnd,
                            width
                    )) {
                        bestStart = runStart;
                        bestEnd = x - 1;
                    }
                }
                runStart = -1;
            }
        }

        if (runStart >= 0 && searchEnd + 1 - runStart >= minimumRun) {
            if (isBetterSplitRun(
                    runStart,
                    searchEnd,
                    bestStart,
                    bestEnd,
                    width
            )) {
                bestStart = runStart;
                bestEnd = searchEnd;
            }
        }

        int split;
        if (bestStart >= 0 && bestEnd >= bestStart) {
            split = Math.round((bestStart + bestEnd) * 0.5f);
        } else {
            split = width / 2;
        }

        int minimumSplit = Math.round(width * 0.42f);
        int maximumSplit = Math.round(width * 0.58f);

        split = Math.max(
                minimumSplit,
                Math.min(maximumSplit, split)
        );

        log(
                "INFO",
                "Dual optical split: " + split
                        + " / " + width
        );

        return split;
    }

    private boolean isBetterSplitRun(
            int start,
            int end,
            int currentStart,
            int currentEnd,
            int width
    ) {

        if (currentStart < 0 || currentEnd < currentStart) {
            return true;
        }

        int currentLength = currentEnd - currentStart + 1;
        int candidateLength = end - start + 1;

        float candidateCenter = (start + end) * 0.5f;
        float currentCenter = (currentStart + currentEnd) * 0.5f;

        float candidateDistance =
                Math.abs(candidateCenter - width * 0.5f);
        float currentDistance =
                Math.abs(currentCenter - width * 0.5f);

        if (candidateDistance + 2f < currentDistance) {
            return true;
        }

        return Math.abs(candidateDistance - currentDistance) <= 2f
                && candidateLength > currentLength;
    }

    /**
     * Finds the circle/ellipse in one optical half and returns a square crop
     * guaranteed to remain inside that optical field.
     */
    private Rect detectOpticalRoi(
            Bitmap source,
            int xStart,
            int xEnd
    ) {

        final int width = source.getWidth();
        final int height = source.getHeight();

        xStart = Math.max(0, Math.min(width - 1, xStart));
        xEnd = Math.max(xStart + 1, Math.min(width, xEnd));

        final float threshold = opticalBlackThreshold(source);

        // Estimate top/bottom of the optical field from vertical occupancy.
        int xSampleStart = xStart + Math.round((xEnd - xStart) * 0.20f);
        int xSampleEnd = xStart + Math.round((xEnd - xStart) * 0.80f);
        xSampleStart = Math.max(xStart, Math.min(xEnd - 1, xSampleStart));
        xSampleEnd = Math.max(xSampleStart + 1, Math.min(xEnd, xSampleEnd));

        int xSampleCount = Math.max(
                9,
                Math.min(
                        48,
                        Math.max(1, (xSampleEnd - xSampleStart) / ROI_SCAN_STEP)
                )
        );

        float[] yProfile = new float[Math.max(1, (height + ROI_SCAN_STEP - 1) / ROI_SCAN_STEP)];
        int[] rowPixels = new int[width];
        int yi = 0;

        for (int y = 0; y < height; y += ROI_SCAN_STEP) {

            source.getPixels(
                    rowPixels,
                    0,
                    width,
                    0,
                    y,
                    width,
                    1
            );

            int nonBlack = 0;
            for (int i = 0; i < xSampleCount; i++) {
                int x = interpolateInt(
                        xSampleStart,
                        xSampleEnd - 1,
                        i,
                        xSampleCount
                );
                if (pixelLuma(rowPixels[x]) > threshold) {
                    nonBlack++;
                }
            }

            yProfile[yi++] = (float) nonBlack / (float) xSampleCount;
        }

        smoothProfile(yProfile, ROI_PROFILE_SMOOTH_RADIUS);

        int[] yRun = bestProfileRun(
                yProfile,
                ROI_PROFILE_COVERAGE,
                yProfile.length / 2
        );

        if (yRun == null) {
            return null;
        }

        int top = yRun[0] * ROI_SCAN_STEP;
        int bottom = Math.min(
                height,
                ((yRun[1] + 1) * ROI_SCAN_STEP)
        );

        float centerY = (top + bottom) * 0.5f;
        float radiusY = Math.max(1f, (bottom - top) * 0.5f);

        // Estimate left/right visible edges around the estimated vertical center.
        int yBandTop = Math.max(0, Math.round(centerY - radiusY * 0.62f));
        int yBandBottom = Math.min(
                height,
                Math.round(centerY + radiusY * 0.62f)
        );

        if (yBandBottom <= yBandTop) {
            return null;
        }

        int xProfileLength = Math.max(
                1,
                (xEnd - xStart + ROI_SCAN_STEP - 1) / ROI_SCAN_STEP
        );
        float[] xProfile = new float[xProfileLength];

        int bandHeight = yBandBottom - yBandTop;
        for (int i = 0; i < xProfileLength; i++) {
            int x = Math.min(
                    xEnd - 1,
                    xStart + i * ROI_SCAN_STEP
            );

            int samples = Math.max(
                    1,
                    (bandHeight + ROI_SCAN_STEP - 1) / ROI_SCAN_STEP
            );

            int nonBlack = 0;
            for (int j = 0; j < samples; j++) {
                int y = Math.min(
                        yBandBottom - 1,
                        yBandTop + j * ROI_SCAN_STEP
                );

                if (pixelLuma(source.getPixel(x, y)) > threshold) {
                    nonBlack++;
                }
            }

            xProfile[i] = (float) nonBlack / (float) samples;
        }

        smoothProfile(xProfile, ROI_PROFILE_SMOOTH_RADIUS);

        int expectedCenterIndex =
                Math.max(
                        0,
                        Math.min(
                                xProfile.length - 1,
                                Math.round(
                                        ((xStart + xEnd) * 0.5f - xStart)
                                                / ROI_SCAN_STEP
                                )
                        )
                );

        int[] xRun = bestProfileRun(
                xProfile,
                ROI_PROFILE_COVERAGE,
                expectedCenterIndex
        );

        if (xRun == null) {
            return null;
        }

        int visibleLeft = xStart + xRun[0] * ROI_SCAN_STEP;
        int visibleRight = Math.min(
                xEnd,
                xStart + (xRun[1] + 1) * ROI_SCAN_STEP
        );

        boolean leftClipped = visibleLeft <= xStart + ROI_SCAN_STEP;
        boolean rightClipped = visibleRight >= xEnd - ROI_SCAN_STEP;

        float centerX;
        float radiusX;

        if (!leftClipped && !rightClipped) {
            centerX = (visibleLeft + visibleRight) * 0.5f;
            radiusX = Math.max(1f, (visibleRight - visibleLeft) * 0.5f);
        } else if (leftClipped && !rightClipped) {
            // The circle continues beyond the left edge of the composite frame.
            radiusX = radiusY;
            centerX = visibleRight - radiusX;
        } else if (!leftClipped && rightClipped) {
            // The circle continues beyond the right edge of the composite frame.
            radiusX = radiusY;
            centerX = visibleLeft + radiusX;
        } else {
            radiusX = radiusY;
            centerX = (xStart + xEnd) * 0.5f;
        }

        // Favor the smaller radius so corners stay inside the actual optical field.
        float safeRadiusX = Math.max(1f, Math.min(radiusX, radiusY));
        float safeRadiusY = Math.max(1f, Math.min(radiusY, radiusX));

        return largestSafeSquare(
                centerX,
                centerY,
                safeRadiusX,
                safeRadiusY,
                width,
                height,
                ROI_SQUARE_SAFETY_FACTOR
        );
    }

    private float opticalBlackThreshold(Bitmap source) {
        float border = estimateBorderBrightness(source);
        return Math.max(
                ROI_BLACK_LUMA_THRESHOLD,
                Math.min(34f, border + 10f)
        );
    }

    private int interpolateInt(int start, int end, int index, int count) {
        if (count <= 1) return start;
        return start
                + Math.round(
                (end - start)
                        * (index / (float) (count - 1))
        );
    }

    private void smoothProfile(float[] profile, int radius) {
        if (profile == null || profile.length < 3 || radius <= 0) return;

        float[] copy = profile.clone();
        for (int i = 0; i < profile.length; i++) {
            int from = Math.max(0, i - radius);
            int to = Math.min(profile.length - 1, i + radius);
            float sum = 0f;
            int count = 0;
            for (int j = from; j <= to; j++) {
                sum += copy[j];
                count++;
            }
            profile[i] = count == 0 ? copy[i] : sum / count;
        }
    }

    /**
     * Returns [start,end] of the strongest contiguous profile run above the
     * threshold. When a preferred index falls inside a run, that run wins.
     */
    private int[] bestProfileRun(
            float[] profile,
            float threshold,
            int preferredIndex
    ) {

        if (profile == null || profile.length == 0) return null;

        ArrayList<int[]> runs = new ArrayList<>();
        int start = -1;

        for (int i = 0; i < profile.length; i++) {
            if (profile[i] >= threshold) {
                if (start < 0) start = i;
            } else if (start >= 0) {
                runs.add(new int[]{start, i - 1});
                start = -1;
            }
        }

        if (start >= 0) {
            runs.add(new int[]{start, profile.length - 1});
        }

        if (runs.isEmpty()) return null;

        for (int[] run : runs) {
            if (preferredIndex >= run[0] && preferredIndex <= run[1]) {
                return run;
            }
        }

        int[] best = runs.get(0);
        for (int[] run : runs) {
            int bestLength = best[1] - best[0];
            int runLength = run[1] - run[0];
            if (runLength > bestLength) {
                best = run;
            }
        }

        return best;
    }

    private float estimateBorderBrightness(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int patchW = Math.max(8, Math.round(width * 0.035f));
        int patchH = Math.max(8, Math.round(height * 0.035f));
        long sum = 0L;
        long count = 0L;

        for (int y = 0; y < patchH; y += ROI_SCAN_STEP) {
            for (int x = 0; x < patchW; x += ROI_SCAN_STEP) {
                int c = source.getPixel(x, y);
                sum += pixelLuma(c);
                count++;

                c = source.getPixel(width - 1 - x, y);
                sum += pixelLuma(c);
                count++;

                c = source.getPixel(x, height - 1 - y);
                sum += pixelLuma(c);
                count++;

                c = source.getPixel(width - 1 - x, height - 1 - y);
                sum += pixelLuma(c);
                count++;
            }
        }

        return count <= 0
                ? 2f
                : ((float) sum / (float) count);
    }

    private int pixelLuma(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (299 * r + 587 * g + 114 * b) / 1000;
    }

    /**
     * Finds the largest axis-aligned square that fits inside an ellipse and
     * also inside the source image. The small safety factor leaves a margin
     * from the dark optical boundary so JPEG edge/vignetting never reaches the
     * output corners.
     */
    private Rect largestSafeSquare(
            float centerX,
            float centerY,
            float radiusX,
            float radiusY,
            int sourceWidth,
            int sourceHeight,
            float safetyFactor
    ) {

        float low = 16f;
        float high = 2f * Math.min(radiusX, radiusY);

        for (int i = 0; i < 32; i++) {
            float side = (low + high) * 0.5f;
            if (squareFitsEllipse(
                    side,
                    centerX,
                    centerY,
                    radiusX,
                    radiusY,
                    sourceWidth,
                    sourceHeight
            )) {
                low = side;
            } else {
                high = side;
            }
        }

        float side = Math.max(
                16f,
                low * Math.max(0.80f, Math.min(1f, safetyFactor))
        );

        side = Math.min(
                side,
                Math.min(sourceWidth, sourceHeight)
        );

        int size = Math.max(16, (int) Math.floor(side));
        size -= size % 2;
        if (size < 16) size = 16;

        float half = size * 0.5f;
        float squareCenterX = clamp(
                centerX,
                half,
                sourceWidth - half
        );
        float squareCenterY = clamp(
                centerY,
                half,
                sourceHeight - half
        );

        // A final conservative pull toward the ellipse center further avoids
        // a black edge when one optical circle is clipped by the source frame.
        int left = Math.max(
                0,
                Math.min(
                        sourceWidth - size,
                        Math.round(squareCenterX - half)
                )
        );
        int top = Math.max(
                0,
                Math.min(
                        sourceHeight - size,
                        Math.round(squareCenterY - half)
                )
        );

        // Verify the square against the ellipse. If rounding pushed a corner
        // onto the boundary, step inward by a small amount until it is safe.
        while (size >= 32) {
            float cx = left + size * 0.5f;
            float cy = top + size * 0.5f;
            if (squareFitsEllipse(
                    size,
                    cx,
                    cy,
                    radiusX,
                    radiusY,
                    sourceWidth,
                    sourceHeight
            )) {
                break;
            }

            size -= 4;
            size -= size % 2;
            if (size < 16) size = 16;

            half = size * 0.5f;
            squareCenterX = clamp(centerX, half, sourceWidth - half);
            squareCenterY = clamp(centerY, half, sourceHeight - half);
            left = Math.max(
                    0,
                    Math.min(
                            sourceWidth - size,
                            Math.round(squareCenterX - half)
                    )
            );
            top = Math.max(
                    0,
                    Math.min(
                            sourceHeight - size,
                            Math.round(squareCenterY - half)
                    )
            );
        }

        return new Rect(
                left,
                top,
                left + size,
                top + size
        );
    }

    private boolean squareFitsEllipse(
            float side,
            float centerX,
            float centerY,
            float radiusX,
            float radiusY,
            int sourceWidth,
            int sourceHeight
    ) {

        if (side <= 0f
                || radiusX <= 0f
                || radiusY <= 0f) {
            return false;
        }

        float half = side * 0.5f;

        if (side > sourceWidth || side > sourceHeight) {
            return false;
        }

        float clampedCenterX = clamp(
                centerX,
                half,
                sourceWidth - half
        );
        float clampedCenterY = clamp(
                centerY,
                half,
                sourceHeight - half
        );

        float dx = Math.abs(clampedCenterX - centerX) + half;
        float dy = Math.abs(clampedCenterY - centerY) + half;

        float normalizedX = dx / radiusX;
        float normalizedY = dy / radiusY;

        return normalizedX * normalizedX
                + normalizedY * normalizedY
                <= 1.0f;
    }

    private float clamp(float value, float min, float max) {
        if (max < min) return (min + max) * 0.5f;
        return Math.max(min, Math.min(max, value));
    }

    private Rect fallbackSquareCrop(
            int width,
            int height,
            float left,
            float top,
            float right,
            float bottom
    ) {

        int rawLeft = Math.max(
                0,
                Math.min(width - 1, Math.round(width * left))
        );
        int rawTop = Math.max(
                0,
                Math.min(height - 1, Math.round(height * top))
        );
        int rawRight = Math.max(
                rawLeft + 1,
                Math.min(width, Math.round(width * right))
        );
        int rawBottom = Math.max(
                rawTop + 1,
                Math.min(height, Math.round(height * bottom))
        );

        float centerY = (rawTop + rawBottom) * 0.5f;
        float radiusY = Math.max(
                1f,
                (rawBottom - rawTop) * 0.5f
        );

        boolean touchesLeft = rawLeft <= 1;
        boolean touchesRight = rawRight >= width - 1;

        float centerX;
        if (touchesLeft && !touchesRight) {
            centerX = rawRight - radiusY;
        } else if (!touchesLeft && touchesRight) {
            centerX = rawLeft + radiusY;
        } else {
            centerX = (rawLeft + rawRight) * 0.5f;
        }

        return largestSafeSquare(
                centerX,
                centerY,
                radiusY,
                radiusY,
                width,
                height,
                0.90f
        );
    }

    private boolean isUsableCrop(Rect rect, int sourceWidth, int sourceHeight) {
        if (rect == null) return false;
        if (rect.left < 0 || rect.top < 0
                || rect.right > sourceWidth || rect.bottom > sourceHeight) {
            return false;
        }

        int width = rect.width();
        int height = rect.height();

        return width == height
                && width >= Math.max(160, sourceWidth / 10)
                && height >= Math.max(160, sourceHeight / 10);
    }

    private String rectToString(Rect rect) {
        return rect == null
                ? "null"
                : rect.left + "," + rect.top + " "
                        + rect.width() + "x" + rect.height();
    }

    /**
     * Returns a single unified crop for every spectral mode.
     *
     * RGB/R/G/B are extracted directly from the left optical crop.
     * NIR is re-sampled into the exact same output coordinate system using
     * the configured two-lens registration transform.
     */
    private byte[] processCompositeForBand(
            Bitmap source,
            String band
    ) {
        return processCompositeForBand(
                source,
                band,
                processingScale
        );
    }

    private byte[] processCompositeForBand(
            Bitmap source,
            String band,
            double requestedProcessingScale
    ) {

        Rect unified =
                unifiedRgbRect();

        if (!isUsableCrop(
                unified,
                source.getWidth(),
                source.getHeight()
        )
                || !isUsableCrop(
                nirCropRect,
                source.getWidth(),
                source.getHeight()
        )) {
            return null;
        }

        final int width = unified.width();
        final int height = unified.height();

        if ("NIR".equals(band)) {
            return buildUnifiedNirJpeg(
                    source,
                    unified,
                    IMAGE_JPEG_QUALITY,
                    requestedProcessingScale
            );
        }

        Bitmap crop =
                Bitmap.createBitmap(
                        source,
                        unified.left,
                        unified.top,
                        width,
                        height
                );

        Bitmap result =
                crop;

        Bitmap scaledResult =
                null;

        try {
            if ("R".equals(band)) {
                result = channelBitmap(crop, 0);
            } else if ("G".equals(band)) {
                result = channelBitmap(crop, 1);
            } else if ("B".equals(band)) {
                result = channelBitmap(crop, 2);
            }

            scaledResult =
                    scaleBitmap(
                            result,
                            requestedProcessingScale
                    );

            return bitmapToJpeg(
                    scaledResult,
                    IMAGE_JPEG_QUALITY
            );

        } finally {
            if (scaledResult != null
                    && scaledResult != result
                    && !scaledResult.isRecycled()) {
                scaledResult.recycle();
            }
            if (result != crop
                    && result != null
                    && !result.isRecycled()) {
                result.recycle();
            }

            if (!crop.isRecycled()) {
                crop.recycle();
            }
        }
    }

    private Rect unifiedRgbRect() {
        if (rgbCropRect == null
                || nirCropRect == null) {
            return null;
        }

        final int rgbSize =
                Math.min(
                        rgbCropRect.width(),
                        rgbCropRect.height()
                );

        final int nirSize =
                Math.min(
                        nirCropRect.width(),
                        nirCropRect.height()
                );

        final int size =
                Math.min(
                        rgbSize,
                        nirSize
                );

        if (size < 16) {
            return null;
        }

        final int left =
                rgbCropRect.left
                        + Math.max(
                                0,
                                (rgbCropRect.width() - size) / 2
                        );

        final int top =
                rgbCropRect.top
                        + Math.max(
                                0,
                                (rgbCropRect.height() - size) / 2
                        );

        return new Rect(
                left,
                top,
                left + size,
                top + size
        );
    }

    private byte[] buildUnifiedNirJpeg(
            Bitmap source,
            Rect unified,
            int quality
    ) {
        return buildUnifiedNirJpeg(
                source,
                unified,
                quality,
                processingScale
        );
    }

    private byte[] buildUnifiedNirJpeg(
            Bitmap source,
            Rect unified,
            int quality,
            double requestedProcessingScale
    ) {
        final int outputWidth =
                unified.width();

        final int outputHeight =
                unified.height();

        final int nirWidth =
                nirCropRect.width();

        final int nirHeight =
                nirCropRect.height();

        final int[] nirPixels =
                new int[nirWidth * nirHeight];

        source.getPixels(
                nirPixels,
                0,
                nirWidth,
                nirCropRect.left,
                nirCropRect.top,
                nirWidth,
                nirHeight
        );

        final int[] outputPixels =
                new int[
                        outputWidth * outputHeight
                ];

        for (int y = 0; y < outputHeight; y++) {
            for (int x = 0; x < outputWidth; x++) {
                final long mapped =
                        mapUnifiedPixelToNir(
                                x,
                                y,
                                outputWidth,
                                outputHeight,
                                nirWidth,
                                nirHeight
                        );

                if (mapped < 0L) {
                    outputPixels[
                            y * outputWidth + x
                    ] = android.graphics.Color.BLACK;
                    continue;
                }

                final int sourceX =
                        (int) (mapped >> 32);

                final int sourceY =
                        (int) mapped;

                final int color =
                        nirPixels[
                                sourceY
                                        * nirWidth
                                        + sourceX
                        ];

                final int value =
                        pixelLuma(color);

                outputPixels[
                        y * outputWidth + x
                ] = android.graphics.Color.rgb(
                        value,
                        value,
                        value
                );
            }
        }

        Bitmap output =
                Bitmap.createBitmap(
                        outputPixels,
                        0,
                        outputWidth,
                        outputWidth,
                        outputHeight,
                        Bitmap.Config.ARGB_8888
                );

        Bitmap scaledOutput =
                null;

        try {
            scaledOutput =
                    scaleBitmap(
                            output,
                            requestedProcessingScale
                    );

            return bitmapToJpeg(
                    scaledOutput,
                    quality
            );
        } finally {
            if (scaledOutput != null
                    && scaledOutput != output
                    && !scaledOutput.isRecycled()) {
                scaledOutput.recycle();
            }

            if (!output.isRecycled()) {
                output.recycle();
            }
        }
    }

    private Bitmap channelBitmap(Bitmap source, int channel) {
        float[] values = new float[]{
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
        };

        if (channel == 0) {
            values[0] = 1f;
            values[5] = 1f;
            values[10] = 1f;
        } else if (channel == 1) {
            values[1] = 1f;
            values[6] = 1f;
            values[11] = 1f;
        } else {
            values[2] = 1f;
            values[7] = 1f;
            values[12] = 1f;
        }

        Bitmap output = Bitmap.createBitmap(
                source.getWidth(),
                source.getHeight(),
                Bitmap.Config.ARGB_8888
        );

        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
        );
        paint.setColorFilter(
                new ColorMatrixColorFilter(
                        new ColorMatrix(values)
                )
        );
        canvas.drawBitmap(source, 0f, 0f, paint);
        return output;
    }

    private Bitmap grayscaleBitmap(Bitmap source) {
        float[] values = new float[]{
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
        };

        Bitmap output = Bitmap.createBitmap(
                source.getWidth(),
                source.getHeight(),
                Bitmap.Config.ARGB_8888
        );

        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG
                        | Paint.FILTER_BITMAP_FLAG
        );
        paint.setColorFilter(
                new ColorMatrixColorFilter(
                        new ColorMatrix(values)
                )
        );
        canvas.drawBitmap(source, 0f, 0f, paint);
        return output;
    }

    private byte[] buildScaledPreviewJpeg(
            Bitmap source,
            double scale
    ) {
        if (source == null || source.isRecycled()) {
            return null;
        }

        Bitmap scaled =
                scaleBitmap(
                        source,
                        scale
                );

        try {
            return bitmapToJpeg(
                    scaled,
                    IMAGE_JPEG_QUALITY
            );
        } finally {
            if (scaled != source
                    && scaled != null
                    && !scaled.isRecycled()) {
                scaled.recycle();
            }
        }
    }

    private Bitmap scaleBitmap(
            Bitmap source,
            double scale
    ) {
        if (source == null
                || source.isRecycled()) {
            return null;
        }

        int width =
                scaledDimension(
                        source.getWidth(),
                        scale
                );

        int height =
                scaledDimension(
                        source.getHeight(),
                        scale
                );

        if (width == source.getWidth()
                && height == source.getHeight()) {
            return source;
        }

        return Bitmap.createScaledBitmap(
                source,
                width,
                height,
                true
        );
    }

    private int scaledDimension(
            int dimension,
            double scale
    ) {
        if (dimension <= 0) {
            return 1;
        }

        double safeScale =
                Double.isNaN(scale)
                        || Double.isInfinite(scale)
                        ? 1.0d
                        : scale;

        safeScale =
                Math.max(
                        0.25d,
                        Math.min(
                                1.0d,
                                safeScale
                        )
                );

        return Math.max(
                1,
                (int) Math.round(
                        dimension * safeScale
                )
        );
    }

    private byte[] bitmapToJpeg(Bitmap bitmap, int quality) {
        if (bitmap == null || bitmap.isRecycled()) return null;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!bitmap.compress(
                Bitmap.CompressFormat.JPEG,
                quality,
                output
        )) {
            return null;
        }

        return output.toByteArray();
    }

    private void emitPreviewFrame(
            byte[] jpeg,
            String band,
            String source,
            boolean processed,
            String previewMode,
            int width,
            int height,
            Rect rect,
            long expectedPreviewGeneration
    ) {
        if (jpeg == null || jpeg.length == 0) {
            return;
        }

        /*
         * Preview events have strict mode ownership.
         *
         * NDVI events are legal only when NDVI is enabled.
         * Normal/RAW events are legal only when NDVI is disabled.
         */
        if (expectedPreviewGeneration
                != previewGeneration.get()) {
            return;
        }

        final boolean isNdviPreview =
                "NDVI".equalsIgnoreCase(
                        previewMode
                );

        if (isNdviPreview != ndviEnabled) {
            return;
        }

        long now =
                System.currentTimeMillis();

        if (isNdviPreview) {
            if (now - lastNdviFrameEventAt
                    < NDVI_EVENT_INTERVAL_MS) {
                return;
            }

            lastNdviFrameEventAt =
                    now;
        } else {
            if (now - lastNormalFrameEventAt
                    < FRAME_EVENT_INTERVAL_MS) {
                return;
            }

            lastNormalFrameEventAt =
                    now;
        }

        HashMap<String, Object> event =
                new HashMap<>();

        event.put("bytes", jpeg);
        event.put("band", band);
        event.put("quad", isQuadMode);
        event.put("grayscale", grayscaleMode);
        event.put("source", source);
        event.put("processed", processed);
        event.put("previewMode", previewMode);
        event.put("captureMode", captureMode);
        event.put(
                "isQuadFrame",
                !processed
        );
        event.put(
                "realSpectralFrame",
                true
        );
        event.put("width", width);
        event.put("height", height);
        event.put("bitDepth", 8);
        event.put(
                "compositeWidth",
                calibratedSourceWidth
        );
        event.put(
                "compositeHeight",
                calibratedSourceHeight
        );
        event.put(
                "cropX",
                rect == null ? 0 : rect.left
        );
        event.put(
                "cropY",
                rect == null ? 0 : rect.top
        );
        event.put(
                "cropWidth",
                rect == null ? 0 : rect.width()
        );
        event.put(
                "cropHeight",
                rect == null ? 0 : rect.height()
        );
        event.put(
                "unifiedSpectralCrop",
                isNdviPreview
                        ? true
                        : !"RAW".equals(previewMode)
        );
        event.put(
                "registrationApplied",
                isNdviPreview
                        ? true
                        : !"RAW".equals(previewMode)
        );
        event.put(
                "registrationModel",
                isNdviPreview
                        ? "CENTERED_AFFINE"
                        : "CENTERED_AFFINE"
        );
        event.put(
                "nirGain",
                lastNdviNirGain
        );
        event.put(
                "previewGeneration",
                expectedPreviewGeneration
        );

        sendEvent(
                "liveviewFrame",
                event
        );

        if (!firstFrameSent
                && expectedPreviewGeneration
                        == previewGeneration.get()
                && isStreaming.get()) {

            firstFrameSent = true;

            sendEvent(
                    "firstLiveviewFrame",
                    true
            );

            updateSystemStatus(
                    "CAMERA READY",
                    true
            );
        }
    }

    private void emitProcessedFrame(
            byte[] jpeg,
            String band,
            String source,
            boolean throttle,
            long expectedPreviewGeneration
    ) {
        Rect rect =
                unifiedRgbRect();

        emitPreviewFrame(
                jpeg,
                band,
                source,
                true,
                "PROCESSED",
                rect == null
                        ? 0
                        : rect.width(),
                rect == null
                        ? 0
                        : rect.height(),
                rect,
                expectedPreviewGeneration
        );
    }

    private void emitRawPreviewFrame(
            byte[] jpeg,
            String band,
            int width,
            int height
    ) {
        emitPreviewFrame(
                jpeg,
                band,
                "FULL_COMPOSITE",
                false,
                "RAW",
                width,
                height,
                null,
                previewGeneration.get()
        );
    }

    /**
     * Legacy compatibility wrapper for the old NIR API path that remains in
     * this single-file transport. The active LANDCAM path no longer uses it;
     * NIR is derived from the right optical ROI of the composite frame.
     */
    private void emitSpectralFrame(
            byte[] jpeg,
            String band,
            String source
    ) {
        emitProcessedFrame(
                jpeg,
                band,
                source,
                false,
                previewGeneration.get()
        );
    }

    private void stopNirPolling() {
        isNirPolling.set(false);
    }

    private void revertToRgbAfterNirFailure() {
        activeSpectralBand = "RGB";
        spectralGeneration.incrementAndGet();
        sendEvent("spectralBandChanged", mapOf("band", "RGB"));
        updateSystemStatus("RGB VIEW", true);
    }

    private void trimStreamBuffer(
            ByteArrayOutputStream accumulator
    ) {

        byte[] data =
                accumulator.toByteArray();

        int lastStart =
                findSeq(
                        data,
                        new byte[]{
                                (byte) 0xFF,
                                (byte) 0xD8
                        },
                        Math.max(
                                0,
                                data.length - 65536
                        )
                );

        accumulator.reset();

        if (lastStart >= 0) {

            accumulator.write(
                    data,
                    lastStart,
                    data.length - lastStart
            );

        } else {

            int keep =
                    Math.min(
                            data.length,
                            65536
                    );

            accumulator.write(
                    data,
                    data.length - keep,
                    keep
            );
        }
    }

    private static int findSeq(
            byte[] data,
            byte[] sequence,
            int offset
    ) {

        if (
                data == null
                        || sequence == null
                        || sequence.length == 0
        ) {

            return -1;
        }

        int start =
                Math.max(
                        0,
                        offset
                );

        outer:
        for (
                int i = start;
                i <= data.length
                        - sequence.length;
                i++
        ) {

            for (
                    int j = 0;
                    j < sequence.length;
                    j++
            ) {

                if (
                        data[i + j]
                                != sequence[j]
                ) {

                    continue outer;
                }
            }

            return i;
        }

        return -1;
    }

    private void refreshLiveviewImpl() {

        stopStreaming();

        updateSystemStatus(
                "REFRESHING LIVE VIEW",
                true
        );

        executor.execute(
                () -> {

                    sleepQuietly(
                            350L
                    );

                    Network network =
                            currentNetwork;

                    CameraEndpoint endpoint =
                            currentEndpoint();

                    if (
                            network == null
                                    || endpoint == null
                    ) {

                        probeCurrentNetworkImpl();
                        return;
                    }

                    isEngineStarting.set(
                            false
                    );

                    long generation =
                            sessionGeneration.get();

                    probeAndStartCamera(
                            network,
                            endpoint,
                            generation
                    );
                }
        );
    }

    private CameraEndpoint currentEndpoint() {

        String api =
                cameraApiUrl;

        String host =
                cameraHost;

        int port =
                cameraPort;

        if (
                api == null
                        || api.isEmpty()
                        || host == null
                        || port <= 0
        ) {

            return null;
        }

        return new CameraEndpoint(
                host,
                port,
                cameraScheme,
                api,
                cameraFriendlyName,
                cameraModel
        );
    }

    private void takePicture() {

        executor.execute(() -> {
            Network network =
                    currentNetwork;

            CameraEndpoint endpoint =
                    currentEndpoint();

            byte[] fallbackFrame =
                    lastCompositeFrameBytes;

            if (network == null
                    || endpoint == null) {
                sendEvent(
                        "captureError",
                        "CAMERA NOT READY"
                );
                return;
            }

            final String requestedMode =
                    captureMode;

            final String requestedPerformance =
                    performanceMode;

            final double requestedProcessingScale =
                    processingScale;

            try {
                JSONObject response =
                        callApiAt(
                                network,
                                endpoint,
                                "actTakePicture",
                                new JSONArray(),
                                API_CONNECT_TIMEOUT_MS,
                                API_READ_TIMEOUT_MS
                        );

                if (hasApiError(response)) {
                    throw new IllegalStateException(
                            "SHUTTER FAILED: "
                                    + apiErrorDescription(response)
                    );
                }

                String imageUrl =
                        findFirstImageUrl(response);

                byte[] composite =
                        null;

                if (imageUrl != null
                        && !imageUrl.isEmpty()) {
                    try {
                        composite =
                                downloadBytes(
                                        network,
                                        normalizeStreamUrl(
                                                imageUrl,
                                                endpoint
                                        ),
                                        15000
                                );
                    } catch (Exception e) {
                        log(
                                "WARN",
                                "Camera capture URL download failed: "
                                        + safeMessage(e)
                        );
                    }
                }

                if (composite == null
                        || composite.length == 0) {
                    composite =
                            fallbackFrame;
                }

                if (composite == null
                        || composite.length == 0) {
                    throw new IllegalStateException(
                            "NO CAPTURE IMAGE AVAILABLE"
                    );
                }

                Bitmap source =
                        BitmapFactory.decodeByteArray(
                                composite,
                                0,
                                composite.length
                        );

                if (source == null) {
                    throw new IllegalStateException(
                            "CAPTURE IMAGE DECODE FAILED"
                    );
                }

                try {
                    String band =
                            activeSpectralBand == null
                                    ? "RGB"
                                    : activeSpectralBand;

                    boolean saveRaw =
                            CAPTURE_MODE_RAW.equals(requestedMode)
                                    || CAPTURE_MODE_BOTH.equals(requestedMode);

                    boolean saveProcessed =
                            CAPTURE_MODE_PROCESSED.equals(requestedMode)
                                    || CAPTURE_MODE_BOTH.equals(requestedMode);

                    String rawFileName =
                            null;

                    String processedFileName =
                            null;

                    String processedBand =
                            null;

                    HashMap<String, Object> data =
                            new HashMap<>();

                    /*
                     * RAW is the exact camera-returned composite. The original
                     * bytes are preserved and are never resized or processed.
                     */
                    if (saveRaw) {
                        rawFileName =
                                saveToGallery(
                                        composite,
                                        "RAW"
                                );
                    }

                    if (saveProcessed) {
                        ensureDualOpticalCalibration(
                                source
                        );

                        byte[] processed;

                        if (ndviEnabled) {
                            /*
                             * NDVI already owns a dedicated capture renderer.
                             * Keep its established full-quality capture path.
                             */
                            processed =
                                    buildNdviJpeg(
                                            source,
                                            false
                                    );
                            processedBand =
                                    "NDVI";
                        } else {
                            processed =
                                    processCompositeForBand(
                                            source,
                                            band,
                                            requestedProcessingScale
                                    );
                            processedBand =
                                    band;
                        }

                        if (processed == null
                                || processed.length == 0) {
                            throw new IllegalStateException(
                                    "FAILED TO BUILD UNIFIED "
                                            + processedBand
                                            + " CROP"
                            );
                        }

                        processedFileName =
                                saveToGallery(
                                        processed,
                                        processedBand
                                );
                    }

                    if (rawFileName == null
                            && processedFileName == null) {
                        throw new IllegalStateException(
                                "NO CAPTURE OUTPUT WAS SAVED"
                        );
                    }

                    /*
                     * Preserve legacy fileName semantics: prefer the processed
                     * file when both outputs were requested.
                     */
                    String fileName =
                            processedFileName != null
                                    ? processedFileName
                                    : rawFileName;

                    data.put(
                            "fileName",
                            fileName
                    );
                    data.put(
                            "captureMode",
                            requestedMode
                    );
                    data.put(
                            "output",
                            requestedMode
                    );
                    data.put(
                            "raw",
                            rawFileName != null
                    );
                    data.put(
                            "processed",
                            processedFileName != null
                    );
                    data.put(
                            "rawFileName",
                            rawFileName
                    );
                    data.put(
                            "processedFileName",
                            processedFileName
                    );
                    data.put(
                            "performance",
                            requestedPerformance
                    );
                    data.put(
                            "processingScale",
                            requestedProcessingScale
                    );
                    data.put(
                            "band",
                            processedBand != null
                                    ? processedBand
                                    : "COMPOSITE"
                    );
                    data.put(
                            "source",
                            processedFileName != null
                                    ? (
                                            ndviEnabled
                                                    ? "UNIFIED_RGB_NIR_REGISTERED_NDVI"
                                                    : "UNIFIED_DUAL_OPTICAL_CROP"
                                    )
                                    : "FULL_COMPOSITE"
                    );
                    data.put(
                            "unifiedSpectralCrop",
                            processedFileName != null
                    );
                    data.put(
                            "registrationApplied",
                            processedFileName != null
                    );
                    data.put(
                            "registrationModel",
                            processedFileName != null
                                    ? "CENTERED_AFFINE"
                                    : "NONE"
                    );
                    data.put(
                            "width",
                            source.getWidth()
                    );
                    data.put(
                            "height",
                            source.getHeight()
                    );
                    data.put(
                            "nirGain",
                            lastNdviNirGain
                    );

                    if (!Double.isNaN(lastNdviValue)) {
                        data.put(
                                "ndvi",
                                lastNdviValue
                        );
                    }

                    sendEvent(
                            "captureSaved",
                            data
                    );
                    sendEvent(
                            "shutterAck",
                            data
                    );

                } finally {
                    source.recycle();
                }

            } catch (Exception e) {
                Log.e(
                        TAG,
                        "Capture failed",
                        e
                );
                sendEvent(
                        "captureError",
                        "CAPTURE FAILED: "
                                + safeMessage(e)
                );
            }
        });
    }

    private byte[] downloadBytes(
            Network network,
            String url,
            int timeoutMs
    ) throws Exception {

        URLConnection raw =
                network.openConnection(
                        new URL(url)
                );

        if (!(raw instanceof HttpURLConnection)) {

            throw new IllegalStateException(
                    "CAPTURE URL IS NOT HTTP"
            );
        }

        HttpURLConnection connection =
                (HttpURLConnection)
                        raw;

        try {

            connection.setRequestMethod(
                    "GET"
            );

            connection.setUseCaches(
                    false
            );

            connection.setConnectTimeout(
                    timeoutMs
            );

            connection.setReadTimeout(
                    timeoutMs
            );

            int code =
                    connection.getResponseCode();

            if (
                    code < 200
                            || code >= 400
            ) {

                throw new IllegalStateException(
                        "CAPTURE HTTP "
                                + code
                );
            }

            return readAllBytes(
                    connection.getInputStream(),
                    30 * 1024 * 1024
            );

        } finally {

            connection.disconnect();
        }
    }

    private byte[] readAllBytes(
            InputStream input,
            int maxBytes
    ) throws Exception {

        if (input == null) {
            return null;
        }

        try (
                InputStream in =
                        new BufferedInputStream(
                                input
                        )
        ) {

            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();

            byte[] buffer =
                    new byte[16384];

            int total = 0;

            int count;

            while (
                    (count =
                            in.read(buffer))
                            >= 0
            ) {

                if (count == 0) {
                    continue;
                }

                total += count;

                if (total > maxBytes) {

                    throw new IllegalStateException(
                            "IMAGE TOO LARGE"
                    );
                }

                output.write(
                        buffer,
                        0,
                        count
                );
            }

            return output.toByteArray();
        }
    }

    private String saveToGallery(
            byte[] jpeg,
            String band
    ) throws Exception {

        String timestamp =
                new SimpleDateFormat(
                        "yyyyMMdd_HHmmss_SSS",
                        Locale.US
                ).format(
                        new Date()
                );

        String safeBand =
                band == null || band.trim().isEmpty()
                        ? "RGB"
                        : band.trim().toUpperCase(Locale.US);

        String displayName =
                "LandCam_"
                        + safeBand
                        + "_"
                        + timestamp
                        + ".jpg";

        ContentResolver resolver =
                context.getContentResolver();

        if (
                Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.Q
        ) {

            ContentValues values =
                    new ContentValues();

            values.put(
                    MediaStore.Images.Media
                            .DISPLAY_NAME,
                    displayName
            );

            values.put(
                    MediaStore.Images.Media
                            .MIME_TYPE,
                    "image/jpeg"
            );

            values.put(
                    MediaStore.Images.Media
                            .RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES
                            + "/LandCam_Monitor"
            );

            values.put(
                    MediaStore.Images.Media
                            .IS_PENDING,
                    1
            );

            android.net.Uri uri =
                    resolver.insert(
                            MediaStore.Images.Media
                                    .EXTERNAL_CONTENT_URI,
                            values
                    );

            if (uri == null) {

                throw new IllegalStateException(
                        "MEDIASTORE INSERT FAILED"
                );
            }

            try {

                OutputStream output =
                        resolver.openOutputStream(
                                uri
                        );

                if (output == null) {

                    throw new IllegalStateException(
                            "NO OUTPUT STREAM"
                    );
                }

                try (
                        OutputStream out =
                                output
                ) {

                    out.write(
                            jpeg
                    );

                    out.flush();
                }

            } catch (Exception e) {

                resolver.delete(
                        uri,
                        null,
                        null
                );

                throw e;
            }

            ContentValues done =
                    new ContentValues();

            done.put(
                    MediaStore.Images.Media
                            .IS_PENDING,
                    0
            );

            resolver.update(
                    uri,
                    done,
                    null,
                    null
            );

        } else {

            File pictures =
                    Environment
                            .getExternalStoragePublicDirectory(
                                    Environment
                                            .DIRECTORY_PICTURES
                            );

            File directory =
                    new File(
                            pictures,
                            "LandCam_Monitor"
                    );

            if (
                    !directory.exists()
                            && !directory.mkdirs()
            ) {

                throw new IllegalStateException(
                        "CANNOT CREATE GALLERY DIRECTORY"
                );
            }

            File file =
                    new File(
                            directory,
                            displayName
                    );

            try (
                    FileOutputStream output =
                            new FileOutputStream(
                                    file
                            )
            ) {

                output.write(
                        jpeg
                );

                output.flush();
            }
        }

        return displayName;
    }

    private void triggerAutoFocusImpl() {

        executor.execute(
                () -> {

                    Network network =
                            currentNetwork;

                    CameraEndpoint endpoint =
                            currentEndpoint();

                    if (
                            network == null
                                    || endpoint == null
                    ) {

                        sendEvent(
                                "engineWarning",
                                "AUTOFOCUS COMMAND FAILED: "
                                        + "CAMERA NOT READY"
                        );

                        return;
                    }

                    try {

                        boolean focusModePrepared =
                                false;

                        // Best effort: configure an actual AF mode when the
                        // connected camera exposes the focus-mode API.
                        try {

                            JSONObject availableModes =
                                    callApiAt(
                                            network,
                                            endpoint,
                                            "getAvailableFocusMode",
                                            new JSONArray(),
                                            HTTP_PROBE_TIMEOUT_MS,
                                            HTTP_PROBE_TIMEOUT_MS
                                    );

                            String mode =
                                    chooseAutofocusMode(
                                            availableModes
                                    );

                            if (mode != null) {

                                JSONObject setMode =
                                        callApiAt(
                                                network,
                                                endpoint,
                                                "setFocusMode",
                                                new JSONArray().put(mode),
                                                API_CONNECT_TIMEOUT_MS,
                                                API_READ_TIMEOUT_MS
                                        );

                                focusModePrepared =
                                        !hasApiError(setMode);
                            }

                        } catch (Exception ignored) {
                            // Not all camera firmware exposes focus-mode APIs.
                        }

                        String directFocusError =
                                null;

                        try {

                            JSONObject response =
                                    callApiAt(
                                            network,
                                            endpoint,
                                            "actFocus",
                                            new JSONArray(),
                                            API_CONNECT_TIMEOUT_MS,
                                            API_READ_TIMEOUT_MS
                                    );

                            if (!hasApiError(response)) {

                                sleepQuietly(600L);

                                try {
                                    callApiAt(
                                            network,
                                            endpoint,
                                            "cancelFocus",
                                            new JSONArray(),
                                            API_CONNECT_TIMEOUT_MS,
                                            API_READ_TIMEOUT_MS
                                    );
                                } catch (Exception ignored) {
                                }

                                sendEvent(
                                        "autofocusDone",
                                        true
                                );
                                return;
                            }

                            directFocusError =
                                    apiErrorDescription(response);

                        } catch (Exception e) {
                            directFocusError =
                                    safeMessage(e);
                        }

                        // Important compatibility path: several supported Sony
                        // camera generations expose actHalfPressShutter rather
                        // than actFocus. A half-press starts the camera's AF
                        // process; cancelHalfPressShutter then releases it.
                        try {

                            JSONObject halfPress =
                                    callApiAt(
                                            network,
                                            endpoint,
                                            "actHalfPressShutter",
                                            new JSONArray(),
                                            API_CONNECT_TIMEOUT_MS,
                                            API_READ_TIMEOUT_MS
                                    );

                            if (!hasApiError(halfPress)) {

                                sleepQuietly(700L);

                                try {
                                    callApiAt(
                                            network,
                                            endpoint,
                                            "cancelHalfPressShutter",
                                            new JSONArray(),
                                            API_CONNECT_TIMEOUT_MS,
                                            API_READ_TIMEOUT_MS
                                    );
                                } catch (Exception ignored) {
                                }

                                sendEvent(
                                        "autofocusDone",
                                        true
                                );
                                return;
                            }

                            String halfPressError =
                                    apiErrorDescription(
                                            halfPress
                                    );

                            sendEvent(
                                    "engineWarning",
                                    "AUTOFOCUS NOT AVAILABLE: "
                                            + "actFocus="
                                            + truncate(
                                            directFocusError == null
                                                    ? "NO RESPONSE"
                                                    : directFocusError,
                                            160
                                    )
                                            + "; halfPress="
                                            + truncate(
                                            halfPressError,
                                            160
                                    )
                            );

                        } catch (Exception halfPressException) {

                            sendEvent(
                                    "engineWarning",
                                    "AUTOFOCUS NOT AVAILABLE: "
                                            + truncate(
                                            directFocusError == null
                                                    ? "NO DIRECT FOCUS"
                                                    : directFocusError,
                                            220
                                    )
                                            + "; halfPress command failed: "
                                            + safeMessage(
                                            halfPressException
                                    )
                            );
                        }

                    } catch (Exception e) {

                        Log.e(
                                TAG,
                                "Autofocus failed",
                                e
                        );

                        sendEvent(
                                "engineWarning",
                                "AUTOFOCUS COMMAND FAILED: "
                                        + safeMessage(e)
                        );
                    }
                }
        );
    }

    private String chooseAutofocusMode(
            JSONObject response
    ) {

        if (response == null || hasApiError(response)) {
            return null;
        }

        ArrayList<String> modes =
                new ArrayList<>();

        collectFocusModes(
                response.opt("result"),
                modes,
                0
        );

        String[] preferred =
                new String[]{
                        "AF-C",
                        "Continuous AF",
                        "AF-S"
                };

        for (String wanted : preferred) {
            for (String mode : modes) {
                if (wanted.equalsIgnoreCase(mode)) {
                    return mode;
                }
            }
        }

        // Some cameras return a single opaque/locale-specific AF mode string.
        // Prefer the first mode that still clearly describes autofocus.
        for (String mode : modes) {
            String lower =
                    mode.toLowerCase(
                            Locale.US
                    );

            if (lower.contains("af")) {
                return mode;
            }
        }

        return null;
    }

    private void collectFocusModes(
            Object value,
            List<String> target,
            int depth
    ) {

        if (value == null
                || depth > 8
                || target == null) {
            return;
        }

        if (value instanceof String) {
            String text =
                    ((String) value).trim();
            if (!text.isEmpty()) {
                target.add(text);
            }
            return;
        }

        if (value instanceof JSONArray) {
            JSONArray array =
                    (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                collectFocusModes(
                        array.opt(i),
                        target,
                        depth + 1
                );
            }
            return;
        }

        if (value instanceof JSONObject) {
            JSONObject object =
                    (JSONObject) value;
            java.util.Iterator<String> keys =
                    object.keys();
            while (keys.hasNext()) {
                collectFocusModes(
                        object.opt(keys.next()),
                        target,
                        depth + 1
                );
            }
        }
    }

    private void disconnectImpl() {

        sessionGeneration.incrementAndGet();

        stopStreaming();

        releaseMulticastLock();

        lastFrameBytes =
                null;

        lastCompositeFrameBytes =
                null;

        firstFrameSent =
                false;

        isEngineStarting.set(
                false
        );

        isDiscoveryRunning.set(
                false
        );

        lastLiveviewUrl =
                null;

        previewGeneration.incrementAndGet();
        ndviRendererMode =
                NDVI_RENDERER_UNKNOWN;
        lastNormalFrameEventAt = 0L;
        lastNdviFrameEventAt = 0L;

        activeSpectralBand =
                "RGB";

        clearCameraEndpoint();

        unregisterCurrentNetworkCallback();

        if (connectivityManager != null) {

            try {

                connectivityManager
                        .bindProcessToNetwork(
                                null
                        );

            } catch (Exception ignored) {
            }
        }

        currentNetwork =
                null;

        updateSystemStatus(
                "CAMERA DISCONNECTED",
                false
        );

        sendEvent(
                "disconnected",
                true
        );
    }

    private void unregisterCurrentNetworkCallback() {

        if (
                connectivityManager != null
                        && networkCallback != null
        ) {

            try {

                connectivityManager
                        .unregisterNetworkCallback(
                                networkCallback
                        );

            } catch (Exception ignored) {
            }

            networkCallback =
                    null;
        }
    }

    private void clearCameraEndpoint() {

        discoveredEndpoint.set(
                null
        );

        cameraHost =
                null;

        cameraPort =
                -1;

        cameraScheme =
                "http";

        cameraApiUrl =
                null;

        /*
         * Reset internal metadata.
         * None of this is exposed directly to UI.
         */
        cameraBrand =
                "UNKNOWN";

        cameraModel =
                "UNKNOWN";

        cameraProtocol =
                UI_PROTOCOL_LABEL;

        cameraFriendlyName =
                "";

        activeSpectralBand =
                "RGB";

        realNirAvailable =
                false;

        nirApiMethod =
                null;

        nirStreamUrl =
                null;

        streamingBand =
                "RGB";

        lastCompositeFrameBytes = null;
        lastFrameBytes = null;
        lastNdviNirGain = 1.0f;
        calibratedSourceWidth = -1;
        calibratedSourceHeight = -1;
        rgbCropRect = null;
        nirCropRect = null;
        lastQuadEventAt = 0L;
        spectralGeneration.incrementAndGet();
        previewGeneration.incrementAndGet();
        ndviRendererMode =
                NDVI_RENDERER_UNKNOWN;
    }

    private void stopStreaming() {

        /*
         * Invalidate all in-flight preview work immediately. The worker uses
         * previewGeneration to self-terminate after the current call returns.
         */
        previewGeneration.incrementAndGet();
        previewProcessingSequence.set(0L);

        isStreaming.set(
                false
        );
        pendingCompositeFrame.set(null);

        HttpURLConnection connection =
                liveviewConnection;

        liveviewConnection =
                null;

        if (connection != null) {

            try {
                connection.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private void logNetworkDetails(
            Network network
    ) {

        LinkProperties lp =
                getLinkProperties(
                        network
                );

        if (lp == null) {

            log(
                    "WARN",
                    "Network properties unavailable"
            );

            return;
        }

        StringBuilder text =
                new StringBuilder(
                        "NETWORK READY"
                );

        text.append(
                " interface="
        ).append(
                lp.getInterfaceName()
        );

        text.append(
                " addresses="
        ).append(
                lp.getLinkAddresses()
        );

        text.append(
                " routes="
        ).append(
                lp.getRoutes()
        );

        text.append(
                " dns="
        ).append(
                lp.getDnsServers()
        );

        log(
                "INFO",
                text.toString()
        );

        String gateway =
                findGateway(
                        lp
                );

        String local =
                findLocalIpv4(
                        lp
                );

        if (local != null) {

            log(
                    "INFO",
                    "LOCAL IPv4: "
                            + local
            );
        }

        if (gateway != null) {

            log(
                    "INFO",
                    "GATEWAY: "
                            + gateway
            );
        }
    }

    private LinkProperties getLinkProperties(
            Network network
    ) {

        ConnectivityManager cm =
                connectivityManager;

        if (
                cm == null
                        || network == null
        ) {

            return null;
        }

        try {

            return cm.getLinkProperties(
                    network
            );

        } catch (Exception e) {

            return null;
        }
    }

    private List<String> collectCandidateHosts(
            Network network
    ) {

        LinkedHashSet<String> candidates =
                new LinkedHashSet<>();

        LinkProperties lp =
                getLinkProperties(
                        network
                );

        if (
                cameraHost != null
                        && isIpv4(cameraHost)
        ) {

            candidates.add(
                    cameraHost
            );
        }

        if (lp != null) {

            for (
                    RouteInfo route
                    : lp.getRoutes()
            ) {

                InetAddress gateway =
                        route.getGateway();

                if (
                        gateway instanceof Inet4Address
                                && !gateway.isAnyLocalAddress()
                                && !gateway.isLoopbackAddress()
                ) {

                    candidates.add(
                            gateway.getHostAddress()
                    );
                }
            }

            for (
                    LinkAddress linkAddress
                    : lp.getLinkAddresses()
            ) {

                if (
                        !(linkAddress.getAddress()
                                instanceof Inet4Address)
                ) {
                    continue;
                }

                Inet4Address local =
                        (Inet4Address)
                                linkAddress.getAddress();

                if (
                        local.isLoopbackAddress()
                                || local.isAnyLocalAddress()
                ) {
                    continue;
                }

                addNearbyHosts(
                        candidates,
                        local,
                        linkAddress
                                .getPrefixLength(),
                        6
                );
            }
        }

        Collections.addAll(
                candidates,
                "192.168.122.1",
                "192.168.1.1",
                "192.168.0.1",
                "192.168.2.1",
                "10.0.0.1",
                "10.0.0.2",
                "169.254.1.1"
        );

        return new ArrayList<>(
                candidates
        );
    }

    private List<String> collectFullScanHosts(
            Network network
    ) {

        LinkedHashSet<String> hosts =
                new LinkedHashSet<>();

        LinkProperties lp =
                getLinkProperties(
                        network
                );

        if (lp == null) {
            return new ArrayList<>();
        }

        for (
                RouteInfo route
                : lp.getRoutes()
        ) {

            InetAddress gateway =
                    route.getGateway();

            if (gateway instanceof Inet4Address) {

                String value =
                        gateway.getHostAddress();

                if (isIpv4(value)) {
                    hosts.add(value);
                }
            }
        }

        for (
                LinkAddress address
                : lp.getLinkAddresses()
        ) {

            if (
                    !(address.getAddress()
                            instanceof Inet4Address)
            ) {
                continue;
            }

            Inet4Address ipv4 =
                    (Inet4Address)
                            address.getAddress();

            if (
                    ipv4.isLoopbackAddress()
                            || ipv4.isAnyLocalAddress()
            ) {
                continue;
            }

            addSubnetHosts(
                    hosts,
                    ipv4,
                    address.getPrefixLength()
            );
        }

        if (hosts.isEmpty()) {

            addSubnetHosts(
                    hosts,
                    parseIpv4(
                            "192.168.122.10"
                    ),
                    24
            );

            addSubnetHosts(
                    hosts,
                    parseIpv4(
                            "192.168.1.10"
                    ),
                    24
            );

            addSubnetHosts(
                    hosts,
                    parseIpv4(
                            "192.168.0.10"
                    ),
                    24
            );
        }

        List<String> result =
                new ArrayList<>(
                        hosts
                );

        if (
                result.size()
                        > DISCOVERY_MAX_HOSTS
        ) {

            return new ArrayList<>(
                    result.subList(
                            0,
                            DISCOVERY_MAX_HOSTS
                    )
            );
        }

        return result;
    }

    private void addNearbyHosts(
            Set<String> candidates,
            Inet4Address localAddress,
            int prefixLength,
            int radius
    ) {

        byte[] raw =
                localAddress.getAddress();

        int ip =
                ipv4ToInt(raw);

        int p =
                Math.max(
                        0,
                        Math.min(
                                prefixLength,
                                32
                        )
                );

        int mask =
                p == 0
                        ? 0
                        : (int)
                                (
                                        0xFFFFFFFFL
                                                << (32 - p)
                                );

        int network =
                ip & mask;

        int host =
                ip & ~mask;

        int start =
                Math.max(
                        1,
                        host - radius
                );

        int end =
                Math.min(
                        (~mask),
                        host + radius
                );

        if (p >= 31) {
            return;
        }

        for (
                int offset = start;
                offset <= end;
                offset++
        ) {

            int value =
                    network | offset;

            if (value != ip) {

                candidates.add(
                        intToIpv4(value)
                );
            }
        }
    }

    private void addSubnetHosts(
            Set<String> hosts,
            Inet4Address localAddress,
            int prefixLength
    ) {

        if (localAddress == null) {
            return;
        }

        int p =
                Math.max(
                        0,
                        Math.min(
                                prefixLength,
                                32
                        )
                );

        if (p >= 31) {
            return;
        }

        int effectivePrefix =
                Math.max(
                        p,
                        24
                );

        byte[] bytes =
                localAddress.getAddress();

        int ip =
                ipv4ToInt(bytes);

        int mask =
                effectivePrefix == 0
                        ? 0
                        : (int)
                                (
                                        0xFFFFFFFFL
                                                << (32 - effectivePrefix)
                                );

        int network =
                ip & mask;

        int hostBits =
                32 - effectivePrefix;

        int maxHost =
                (1 << Math.min(
                        hostBits,
                        8
                )) - 1;

        for (
                int i = 1;
                i < maxHost;
                i++
        ) {

            int candidate =
                    network | i;

            if (candidate != ip) {

                hosts.add(
                        intToIpv4(
                                candidate
                        )
                );
            }

            if (
                    hosts.size()
                            >= DISCOVERY_MAX_HOSTS
            ) {

                return;
            }
        }
    }

    private void acquireMulticastLock() {

        try {

            WifiManager wifiManager =
                    (WifiManager)
                            context.getSystemService(
                                            Context.WIFI_SERVICE
                                    );

            if (wifiManager == null) {
                return;
            }

            WifiManager.MulticastLock existing =
                    multicastLock;

            if (
                    existing != null
                            && existing.isHeld()
            ) {

                return;
            }

            WifiManager.MulticastLock lock =
                    wifiManager.createMulticastLock(
                            "LandCamSSDP"
                    );

            lock.setReferenceCounted(
                    false
            );

            lock.acquire();

            multicastLock =
                    lock;

            log(
                    "INFO",
                    "Wi-Fi multicast lock acquired"
            );

        } catch (Exception e) {

            log(
                    "WARN",
                    "Could not acquire Wi-Fi multicast lock: "
                            + safeMessage(e)
            );
        }
    }

    private void releaseMulticastLock() {

        WifiManager.MulticastLock lock =
                multicastLock;

        multicastLock =
                null;

        if (lock == null) {
            return;
        }

        try {

            if (lock.isHeld()) {
                lock.release();
            }

        } catch (Exception ignored) {
        }
    }

    private boolean isCurrentSession(
            long generation,
            Network network
    ) {

        return generation
                == sessionGeneration.get()
                && network != null
                && network.equals(
                currentNetwork
        );
    }

    private JSONObject trySetBestFocusMode(
            Network network,
            CameraEndpoint endpoint
    ) throws Exception {

        String[] modes =
                new String[]{
                        "AF-C",
                        "Continuous AF",
                        "AF-S"
                };

        JSONObject last =
                null;

        for (
                String mode
                : modes
        ) {

            JSONArray params =
                    new JSONArray();

            params.put(
                    mode
            );

            last =
                    callApiAt(
                            network,
                            endpoint,
                            "setFocusMode",
                            params,
                            API_CONNECT_TIMEOUT_MS,
                            API_READ_TIMEOUT_MS
                    );

            if (!hasApiError(last)) {

                log(
                        "INFO",
                        "Focus mode selected: "
                                + mode
                );

                return last;
            }

            String error =
                    apiErrorDescription(
                            last
                    ).toLowerCase(
                            Locale.US
                    );

            if (
                    !(
                            error.contains("invalid")
                                    || error.contains(
                                    "not supported"
                            )
                                    || error.contains(
                                    "unsupported"
                            )
                                    || error.contains(
                                    "argument"
                            )
                    )
            ) {

                return last;
            }
        }

        return last;
    }

    private String chooseActionUrl(
            String xml
    ) {

        Matcher matcher =
                XML_ACTION_URL_PATTERN
                        .matcher(
                                xml == null
                                        ? ""
                                        : xml
                        );

        while (
                matcher.find()
        ) {

            String value =
                    cleanXmlValue(
                            matcher.group(1)
                    );

            if (
                    value != null
                            && !value.isEmpty()
            ) {

                return value;
            }
        }

        return null;
    }

    private List<String> buildCameraApiCandidates(
            String actionUrl
    ) {

        LinkedHashSet<String> result =
                new LinkedHashSet<>();

        if (
                actionUrl == null
                        || actionUrl.trim().isEmpty()
        ) {

            return new ArrayList<>();
        }

        String base =
                actionUrl.trim();

        while (base.endsWith("/")) {

            base =
                    base.substring(
                            0,
                            base.length() - 1
                    );
        }

        String lower =
                base.toLowerCase(
                        Locale.US
                );

        if (
                lower.endsWith(
                        "/sony/camera"
                )
        ) {

            result.add(
                    base
            );

        } else if (
                lower.endsWith(
                        "/sony"
                )
        ) {

            result.add(
                    base + "/camera"
            );

            result.add(
                    base
            );

        } else if (
                lower.endsWith(
                        "/camera"
                )
        ) {

            result.add(
                    base
            );

        } else {

            result.add(
                    base + "/camera"
            );

            result.add(
                    base + "/sony/camera"
            );

            result.add(
                    base
            );
        }

        return new ArrayList<>(
                result
        );
    }

    private String normalizeApiUrl(
            String apiUrl
    ) {

        if (apiUrl == null) {
            return null;
        }

        String value =
                apiUrl.trim();

        while (
                value.endsWith("/")
        ) {

            value =
                    value.substring(
                            0,
                            value.length() - 1
                    );
        }

        return value;
    }

    private int effectivePort(
            String urlText,
            int fallbackPort
    ) {

        try {

            URL url =
                    new URL(
                            urlText
                    );

            if (url.getPort() > 0) {

                return url.getPort();
            }

            return url.getDefaultPort() > 0
                    ? url.getDefaultPort()
                    : fallbackPort;

        } catch (Exception e) {

            return fallbackPort;
        }
    }

    private String schemeFromUrl(
            String urlText
    ) {

        try {

            return new URL(
                    urlText
            ).getProtocol();

        } catch (Exception e) {

            return "http";
        }
    }

    private String normalizeStreamUrl(
            String urlText,
            CameraEndpoint endpoint
    ) {

        try {

            URL source =
                    new URL(
                            urlText
                    );

            String scheme =
                    source.getProtocol();

            String host =
                    endpoint.host;

            int port =
                    source.getPort();

            if (port <= 0) {
                port =
                        source.getDefaultPort();
            }

            URL normalized =
                    new URL(
                            scheme,
                            host,
                            port,
                            source.getFile()
                    );

            return normalized.toString();

        } catch (Exception e) {

            return urlText;
        }
    }

    private String findGateway(
            LinkProperties lp
    ) {

        if (lp == null) {
            return null;
        }

        for (
                RouteInfo route
                : lp.getRoutes()
        ) {

            InetAddress gateway =
                    route.getGateway();

            if (
                    gateway instanceof Inet4Address
                            && !gateway.isAnyLocalAddress()
                            && !gateway.isLoopbackAddress()
            ) {

                return gateway.getHostAddress();
            }
        }

        return null;
    }

    private String findLocalIpv4(
            LinkProperties lp
    ) {

        if (lp == null) {
            return null;
        }

        for (
                LinkAddress address
                : lp.getLinkAddresses()
        ) {

            if (
                    address.getAddress()
                            instanceof Inet4Address
            ) {

                Inet4Address ipv4 =
                        (Inet4Address)
                                address.getAddress();

                if (
                        !ipv4.isLoopbackAddress()
                                && !ipv4.isAnyLocalAddress()
                ) {

                    return ipv4.getHostAddress();
                }
            }
        }

        return null;
    }

    private Set<String> extractMethodNames(
            JSONObject response
    ) {

        LinkedHashSet<String> methods =
                new LinkedHashSet<>();

        if (response == null) {
            return methods;
        }

        Object result =
                response.opt(
                        "result"
                );

        collectStringValues(
                result,
                methods,
                0
        );

        return methods;
    }

    private void collectStringValues(
            Object value,
            Set<String> target,
            int depth
    ) {

        if (
                value == null
                        || depth > 8
        ) {

            return;
        }

        if (value instanceof String) {

            String text =
                    ((String) value)
                            .trim();

            if (text.isEmpty()) {
                return;
            }

            // Camera Remote API responses commonly use names such as
            // "camera/actFocus". Normalize them to "actFocus" so capability
            // checks work consistently across firmware versions.
            String[] tokens =
                    text.split("[:,\\s]+");

            for (String token : tokens) {
                String method =
                        normalizeApiMethodName(token);

                if (method.matches(
                        "[A-Za-z_][A-Za-z0-9_]*"
                )) {
                    target.add(method);
                }
            }

            return;
        }

        if (value instanceof JSONArray) {

            JSONArray array =
                    (JSONArray) value;

            for (int i = 0; i < array.length(); i++) {
                collectStringValues(
                        array.opt(i),
                        target,
                        depth + 1
                );
            }

            return;
        }

        if (value instanceof JSONObject) {

            JSONObject object =
                    (JSONObject) value;

            java.util.Iterator<String> keys =
                    object.keys();

            while (keys.hasNext()) {
                collectStringValues(
                        object.opt(keys.next()),
                        target,
                        depth + 1
                );
            }
        }
    }

    private String normalizeApiMethodName(
            String raw
    ) {

        if (raw == null) {
            return "";
        }

        String text =
                raw.trim();

        int slash =
                text.lastIndexOf('/');

        if (slash >= 0
                && slash < text.length() - 1) {
            text =
                    text.substring(
                            slash + 1
                    );
        }

        int dot =
                text.lastIndexOf('.');

        if (dot >= 0
                && dot < text.length() - 1
                && !text.startsWith("http")) {
            String suffix =
                    text.substring(dot + 1);
            if (suffix.matches(
                    "[A-Za-z_][A-Za-z0-9_]*"
            )) {
                text = suffix;
            }
        }

        return text.trim();
    }

    private boolean hasApiResult(
            JSONObject response
    ) {

        return response != null
                && response.has("result")
                && !response.isNull(
                "result"
        );
    }

    private boolean hasApiError(
            JSONObject response
    ) {

        return response != null
                && response.has("error")
                && !response.isNull(
                "error"
        );
    }

    private boolean isBenignAlreadyActiveError(
            JSONObject response
    ) {

        String description =
                apiErrorDescription(
                        response
                ).toLowerCase(
                        Locale.US
                );

        return description.contains(
                "not available now"
        )
                || description.contains(
                "already"
        )
                || description.contains(
                "busy"
        )
                || description.contains(
                "recording"
        );
    }

    private String apiErrorDescription(
            JSONObject response
    ) {

        if (response == null) {
            return "NO RESPONSE";
        }

        if (!hasApiError(
                response
        )) {

            return "NO API ERROR";
        }

        Object error =
                response.opt(
                        "error"
                );

        if (error == null) {
            return "UNKNOWN API ERROR";
        }

        return truncate(
                String.valueOf(
                        error
                ),
                300
        );
    }

    private String findFirstString(
            JSONObject object,
            String... keys
    ) {

        if (
                object == null
                        || keys == null
        ) {

            return null;
        }

        for (
                String key
                : keys
        ) {

            String found =
                    findFirstStringRecursive(
                            object,
                            key,
                            0
                    );

            if (
                    found != null
                            && !found.isEmpty()
            ) {

                return found;
            }
        }

        return null;
    }

    private String findFirstStringRecursive(
            Object value,
            String wantedKey,
            int depth
    ) {

        if (
                value == null
                        || depth > 8
        ) {

            return null;
        }

        if (value instanceof JSONObject) {

            JSONObject object =
                    (JSONObject)
                            value;

            if (
                    object.has(
                            wantedKey
                    )
            ) {

                Object candidate =
                        object.opt(
                                wantedKey
                        );

                if (
                        candidate instanceof String
                ) {

                    return (
                            (String)
                                    candidate
                    ).trim();
                }
            }

            java.util.Iterator<String>
                    keys =
                    object.keys();

            while (
                    keys.hasNext()
            ) {

                Object child =
                        object.opt(
                                keys.next()
                        );

                String found =
                        findFirstStringRecursive(
                                child,
                                wantedKey,
                                depth + 1
                        );

                if (
                        found != null
                                && !found.isEmpty()
                ) {

                    return found;
                }
            }

        } else if (
                value instanceof JSONArray
        ) {

            JSONArray array =
                    (JSONArray)
                            value;

            for (
                    int i = 0;
                    i < array.length();
                    i++
            ) {

                String found =
                        findFirstStringRecursive(
                                array.opt(i),
                                wantedKey,
                                depth + 1
                        );

                if (
                        found != null
                                && !found.isEmpty()
                ) {

                    return found;
                }
            }
        }

        return null;
    }

    private String findUrlInJson(
            JSONObject object
    ) {

        return findUrlRecursive(
                object,
                0,
                false
        );
    }

    private String findFirstImageUrl(
            JSONObject object
    ) {

        return findUrlRecursive(
                object,
                0,
                true
        );
    }

    private String findUrlRecursive(
            Object value,
            int depth,
            boolean imageOnly
    ) {

        if (
                value == null
                        || depth > 8
        ) {

            return null;
        }

        if (value instanceof String) {

            String text =
                    ((String) value)
                            .trim();

            String lower =
                    text.toLowerCase(
                            Locale.US
                    );

            if (
                    !lower.startsWith(
                            "http://"
                    )
                            && !lower.startsWith(
                            "https://"
                    )
            ) {

                return null;
            }

            if (imageOnly) {

                if (
                        lower.contains(".jpg")
                                || lower.contains(".jpeg")
                                || lower.contains("postview")
                                || lower.contains("image")
                ) {

                    return text;
                }

                return null;
            }

            if (
                    lower.contains("liveview")
                            || lower.contains(
                            "liveviewstream"
                    )
                            || lower.contains(
                            "image/jpeg"
                    )
            ) {

                return text;
            }

            return text;
        }

        if (value instanceof JSONArray) {

            JSONArray array =
                    (JSONArray)
                            value;

            for (
                    int i = 0;
                    i < array.length();
                    i++
            ) {

                String found =
                        findUrlRecursive(
                                array.opt(i),
                                depth + 1,
                                imageOnly
                        );

                if (found != null) {
                    return found;
                }
            }

            return null;
        }

        if (value instanceof JSONObject) {

            JSONObject object =
                    (JSONObject)
                            value;

            java.util.Iterator<String>
                    keys =
                    object.keys();

            while (
                    keys.hasNext()
            ) {

                String found =
                        findUrlRecursive(
                                object.opt(
                                        keys.next()
                                ),
                                depth + 1,
                                imageOnly
                        );

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private String firstXmlValue(
            Pattern pattern,
            String xml
    ) {

        if (
                pattern == null
                        || xml == null
        ) {

            return null;
        }

        Matcher matcher =
                pattern.matcher(
                        xml
                );

        if (!matcher.find()) {
            return null;
        }

        return cleanXmlValue(
                matcher.group(1)
        );
    }

    private String cleanXmlValue(
            String value
    ) {

        if (value == null) {
            return null;
        }

        String text =
                value
                        .replace(
                                "<![CDATA[",
                                ""
                        )
                        .replace(
                                "]]>",
                                ""
                        )
                        .trim();

        text =
                text
                        .replace(
                                "&amp;",
                                "&"
                        )
                        .replace(
                                "&lt;",
                                "<"
                        )
                        .replace(
                                "&gt;",
                                ">"
                        )
                        .replace(
                                "&quot;",
                                "\""
                        )
                        .replace(
                                "&apos;",
                                "'"
                        );

        return text;
    }



    private void sendEvent(String type, Object data) {
        if (type == null || type.trim().isEmpty()) {
            return;
        }

        if (listener != null) {
            try {
                listener.onNativeEvent(type, data);
            } catch (Exception e) {
                Log.e(TAG, "Native event callback failed", e);
            }
        }
    }

    private void onEngineError(String message, Throwable error) {
        if (listener != null) {
            try {
                listener.onEngineError(message, error);
            } catch (Exception callbackError) {
                Log.e(TAG, "Engine error callback failed", callbackError);
            }
        }
    }

    private void updateSystemStatus(
            String status,
            boolean active
    ) {

        HashMap<String, Object> data =
                new HashMap<>();

        data.put(
                "status",
                status
        );

        data.put(
                "active",
                active
        );

        if (cameraHost != null) {

            data.put(
                    "host",
                    cameraHost
            );
        }

        if (cameraPort > 0) {

            data.put(
                    "port",
                    cameraPort
            );
        }

        /*
         * IMPORTANT:
         * Do not put brand, model, or friendlyName here.
         */
        data.put(
                "protocol",
                UI_PROTOCOL_LABEL
        );

        sendEvent(
                "systemStatus",
                data
        );
    }

    private void log(
            String level,
            String message
    ) {

        HashMap<String, Object> data =
                new HashMap<>();

        data.put(
                "level",
                level == null
                        ? "INFO"
                        : level.toUpperCase(
                        Locale.US
                )
        );

        data.put(
                "message",
                message == null
                        ? ""
                        : message
        );

        data.put(
                "time",
                new SimpleDateFormat(
                        "HH:mm:ss.SSS",
                        Locale.US
                ).format(
                        new Date()
                )
        );

        sendEvent(
                "log",
                data
        );
    }

    private HashMap<String, Object> mapOf(
            String key,
            Object value
    ) {

        HashMap<String, Object> map =
                new HashMap<>();

        map.put(
                key,
                value
        );

        return map;
    }

    private static String safeMessage(
            Throwable throwable
    ) {

        if (throwable == null) {
            return "UNKNOWN ERROR";
        }

        String message =
                throwable.getMessage();

        if (
                message == null
                        || message.trim().isEmpty()
        ) {

            return throwable
                    .getClass()
                    .getSimpleName();
        }

        return message;
    }

    private static String truncate(
            String value,
            int max
    ) {

        if (value == null) {
            return "";
        }

        if (value.length() <= max) {
            return value;
        }

        return value.substring(
                0,
                Math.max(
                        0,
                        max
                )
        ) + "...";
    }

    private static void sleepQuietly(
            long millis
    ) {

        try {

            Thread.sleep(
                    millis
            );

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();
        }
    }

    private static boolean isIpv4(
            String host
    ) {

        if (host == null) {
            return false;
        }

        String[] parts =
                host.split(
                        "\\."
                );

        if (parts.length != 4) {
            return false;
        }

        for (
                String part
                : parts
        ) {

            try {

                int value =
                        Integer.parseInt(
                                part
                        );

                if (
                        value < 0
                                || value > 255
                ) {

                    return false;
                }

            } catch (
                    NumberFormatException e
            ) {

                return false;
            }
        }

        return true;
    }

    private static Inet4Address parseIpv4(
            String host
    ) {

        try {

            InetAddress address =
                    InetAddress.getByName(
                            host
                    );

            return address
                    instanceof Inet4Address
                    ? (Inet4Address)
                    address
                    : null;

        } catch (Exception e) {

            return null;
        }
    }

    private static int ipv4ToInt(
            byte[] bytes
    ) {

        return (
                ((bytes[0] & 0xFF) << 24)
                        | ((bytes[1] & 0xFF) << 16)
                        | ((bytes[2] & 0xFF) << 8)
                        | (bytes[3] & 0xFF)
        );
    }

    private static String intToIpv4(
            int value
    ) {

        return (
                ((value >>> 24) & 0xFF)
                        + "."
                        + ((value >>> 16) & 0xFF)
                        + "."
                        + ((value >>> 8) & 0xFF)
                        + "."
                        + (value & 0xFF)
        );
    }

    public void shutdown() {
        try {
            disconnectImpl();
        } catch (Exception e) {
            Log.w(TAG, "Engine disconnect during shutdown failed", e);
        }

        try {
            executor.shutdownNow();
        } catch (Exception ignored) {
        }

        synchronized (gpuNdviLock) {
            releaseGpuNdviRendererLocked();
            gpuNdviSurfaceTexture = null;
        }

        try {
            discoveryExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
    }

    public void destroy() {
        ndviEnabled = false;
        lastNdviEventAt = 0L;
        lastNdviPreviewAt = 0L;
        lastNdviComputedAt = 0L;
        lastNdviValue = Double.NaN;
        lastNdviValidPixels = 0;
        lastNdviNirGain = 1.0f;
        lastNdviCalibrationAt = 0L;
        ndviRendererMode = NDVI_RENDERER_UNKNOWN;
        previewGeneration.incrementAndGet();
        shutdown();
    }

    /**
     * Minimal OpenGL ES 2.0 offscreen/surface renderer for the live NDVI path.
     *
     * The shader reads ONLY:
     *   - red channel from the RGB optical ROI
     *   - NIR intensity from the NIR optical ROI
     *
     * It writes ONLY the false-colour NDVI result. No RGB base image is used.
     */
    private static final class GpuNdviRenderer {

        private static final String VERTEX_SHADER =
                "attribute vec2 aPosition;"
                        + "varying vec2 vUv;"
                        + "void main(){"
                        + "  vUv = vec2(aPosition.x * 0.5 + 0.5,"
                        + "              0.5 - aPosition.y * 0.5);"
                        + "  gl_Position = vec4(aPosition, 0.0, 1.0);"
                        + "}";

        private static final String FRAGMENT_SHADER =
                "precision mediump float;"
                        + "uniform sampler2D uComposite;"
                        + "uniform vec4 uRgbRect;"
                        + "uniform vec4 uNirRect;"
                        + "uniform vec2 uScale;"
                        + "uniform vec2 uShiftNorm;"
                        + "uniform vec2 uRot;"
                        + "uniform float uGain;"
                        + "uniform float uMinSignal;"
                        + "varying vec2 vUv;"
                        + ""
                        + "vec3 ndviColor(float ndvi){"
                        + "  float t = clamp((ndvi + 1.0) * 0.5, 0.0, 1.0);"
                        + "  vec3 red = vec3(0.84, 0.00, 0.08);"
                        + "  vec3 yellow = vec3(1.00, 0.91, 0.00);"
                        + "  vec3 green = vec3(0.04, 0.62, 0.18);"
                        + "  if (t < 0.5) return mix(red, yellow, t * 2.0);"
                        + "  return mix(yellow, green, (t - 0.5) * 2.0);"
                        + "}"
                        + ""
                        + "void main(){"
                        + "  vec2 rgbLocal = vUv;"
                        + "  vec2 shifted = rgbLocal - vec2(0.5);"
                        + "  shifted -= uShiftNorm;"
                        + "  shifted = vec2("
                        + "      shifted.x * uRot.x + shifted.y * uRot.y,"
                        + "     -shifted.x * uRot.y + shifted.y * uRot.x"
                        + "  );"
                        + "  shifted /= max(uScale, vec2(0.001));"
                        + "  vec2 nirLocal = shifted + vec2(0.5);"
                        + ""
                        + "  if (nirLocal.x < 0.0 || nirLocal.x > 1.0"
                        + "      || nirLocal.y < 0.0 || nirLocal.y > 1.0) {"
                        + "    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);"
                        + "    return;"
                        + "  }"
                        + ""
                        + "  vec2 rgbTopUv = uRgbRect.xy + rgbLocal * uRgbRect.zw;"
                        + "  vec2 nirTopUv = uNirRect.xy + nirLocal * uNirRect.zw;"
                        + ""
                        + "  vec2 rgbUv = vec2(rgbTopUv.x, 1.0 - rgbTopUv.y);"
                        + "  vec2 nirUv = vec2(nirTopUv.x, 1.0 - nirTopUv.y);"
                        + ""
                        + "  vec4 rgbSample = texture2D(uComposite, rgbUv);"
                        + "  vec4 nirSample = texture2D(uComposite, nirUv);"
                        + ""
                        + "  float red = rgbSample.r;"
                        + "  float nir = dot(nirSample.rgb, vec3(0.299, 0.587, 0.114));"
                        + "  nir *= uGain;"
                        + ""
                        + "  float denom = nir + red;"
                        + "  if (red < uMinSignal || nir < uMinSignal || denom < 0.047) {"
                        + "    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);"
                        + "    return;"
                        + "  }"
                        + ""
                        + "  float ndvi = clamp((nir - red) / denom, -1.0, 1.0);"
                        + "  gl_FragColor = vec4(ndviColor(ndvi), 1.0);"
                        + "}";

        private final Surface surface;
        private final int outputWidth;
        private final int outputHeight;

        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        private int program = 0;
        private int compositeTexture = 0;
        private int textureWidth = -1;
        private int textureHeight = -1;
        private boolean released = false;

        private int positionHandle = -1;
        private int compositeHandle = -1;
        private int rgbRectHandle = -1;
        private int nirRectHandle = -1;
        private int scaleHandle = -1;
        private int shiftHandle = -1;
        private int rotHandle = -1;
        private int gainHandle = -1;
        private int minSignalHandle = -1;

        private FloatBuffer vertexBuffer;

        GpuNdviRenderer(
                Surface surface,
                int outputWidth,
                int outputHeight
        ) {
            this.surface = surface;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
        }

        private void ensureGl() {
            if (released) {
                throw new IllegalStateException("GPU renderer already released");
            }

            if (display != EGL14.EGL_NO_DISPLAY) {
                return;
            }

            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) {
                throw new IllegalStateException("EGL display unavailable");
            }

            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw new IllegalStateException("EGL initialize failed");
            }

            int[] configAttributes = new int[]{
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_NONE
            };

            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(
                    display,
                    configAttributes,
                    0,
                    configs,
                    0,
                    1,
                    numConfigs,
                    0
            ) || numConfigs[0] == 0) {
                throw new IllegalStateException("EGL config unavailable");
            }

            EGLConfig config = configs[0];
            int[] contextAttributes = new int[]{
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };

            context = EGL14.eglCreateContext(
                    display,
                    config,
                    EGL14.EGL_NO_CONTEXT,
                    contextAttributes,
                    0
            );

            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                throw new IllegalStateException("EGL context creation failed");
            }

            eglSurface = EGL14.eglCreateWindowSurface(
                    display,
                    config,
                    surface,
                    new int[]{EGL14.EGL_NONE},
                    0
            );

            if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                throw new IllegalStateException("EGL window surface creation failed");
            }

            if (!EGL14.eglMakeCurrent(
                    display,
                    eglSurface,
                    eglSurface,
                    context
            )) {
                throw new IllegalStateException("EGL make-current failed");
            }

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            compositeHandle = GLES20.glGetUniformLocation(program, "uComposite");
            rgbRectHandle = GLES20.glGetUniformLocation(program, "uRgbRect");
            nirRectHandle = GLES20.glGetUniformLocation(program, "uNirRect");
            scaleHandle = GLES20.glGetUniformLocation(program, "uScale");
            shiftHandle = GLES20.glGetUniformLocation(program, "uShiftNorm");
            rotHandle = GLES20.glGetUniformLocation(program, "uRot");
            gainHandle = GLES20.glGetUniformLocation(program, "uGain");
            minSignalHandle = GLES20.glGetUniformLocation(program, "uMinSignal");

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            compositeTexture = textures[0];

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, compositeTexture);
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST
            );
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_NEAREST
            );
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE
            );
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE
            );

            ByteBuffer vertexBytes = ByteBuffer
                    .allocateDirect(6 * 4)
                    .order(ByteOrder.nativeOrder());
            vertexBuffer = vertexBytes.asFloatBuffer();
            vertexBuffer.put(new float[]{
                    -1f, -1f,
                     3f, -1f,
                    -1f,  3f
            });
            vertexBuffer.position(0);

            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
        }

        void render(
                Bitmap source,
                Rect rgb,
                Rect nir,
                float gain,
                float scaleX,
                float scaleY,
                float shiftX,
                float shiftY,
                float cos,
                float sin
        ) {
            ensureGl();

            if (source.getWidth() != textureWidth
                    || source.getHeight() != textureHeight) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, compositeTexture);
                GLUtils.texImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        source,
                        0
                );
                textureWidth = source.getWidth();
                textureHeight = source.getHeight();
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, compositeTexture);
                GLUtils.texSubImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        0,
                        0,
                        source
                );
            }

            GLES20.glViewport(0, 0, outputWidth, outputHeight);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(program);

            vertexBuffer.position(0);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(
                    positionHandle,
                    2,
                    GLES20.GL_FLOAT,
                    false,
                    0,
                    vertexBuffer
            );

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, compositeTexture);
            GLES20.glUniform1i(compositeHandle, 0);

            GLES20.glUniform4f(
                    rgbRectHandle,
                    (float) rgb.left / (float) textureWidth,
                    (float) rgb.top / (float) textureHeight,
                    (float) rgb.width() / (float) textureWidth,
                    (float) rgb.height() / (float) textureHeight
            );

            GLES20.glUniform4f(
                    nirRectHandle,
                    (float) nir.left / (float) textureWidth,
                    (float) nir.top / (float) textureHeight,
                    (float) nir.width() / (float) textureWidth,
                    (float) nir.height() / (float) textureHeight
            );

            GLES20.glUniform2f(
                    scaleHandle,
                    Math.max(0.001f, Math.abs(scaleX)),
                    Math.max(0.001f, Math.abs(scaleY))
            );

            GLES20.glUniform2f(
                    shiftHandle,
                    shiftX / Math.max(1f, (float) rgb.width()),
                    shiftY / Math.max(1f, (float) rgb.height())
            );

            GLES20.glUniform2f(
                    rotHandle,
                    cos,
                    sin
            );

            GLES20.glUniform1f(
                    gainHandle,
                    Math.max(0.01f, gain)
            );

            GLES20.glUniform1f(
                    minSignalHandle,
                    GPU_NDVI_MIN_SIGNAL
            );

            GLES20.glDrawArrays(
                    GLES20.GL_TRIANGLES,
                    0,
                    3
            );

            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

            if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                throw new IllegalStateException("EGL swap failed");
            }
        }

        private static int createProgram(
                String vertexSource,
                String fragmentSource
        ) {
            int vertex = compileShader(
                    GLES20.GL_VERTEX_SHADER,
                    vertexSource
            );
            int fragment = compileShader(
                    GLES20.GL_FRAGMENT_SHADER,
                    fragmentSource
            );

            int program = GLES20.glCreateProgram();
            if (program == 0) {
                throw new IllegalStateException("GL program creation failed");
            }

            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);

            int[] linked = new int[1];
            GLES20.glGetProgramiv(
                    program,
                    GLES20.GL_LINK_STATUS,
                    linked,
                    0
            );

            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);

            if (linked[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(program);
                GLES20.glDeleteProgram(program);
                throw new IllegalStateException(
                        "GL program link failed: " + log
                );
            }

            return program;
        }

        private static int compileShader(
                int type,
                String source
        ) {
            int shader = GLES20.glCreateShader(type);
            if (shader == 0) {
                throw new IllegalStateException("GL shader creation failed");
            }

            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);

            int[] compiled = new int[1];
            GLES20.glGetShaderiv(
                    shader,
                    GLES20.GL_COMPILE_STATUS,
                    compiled,
                    0
            );

            if (compiled[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException(
                        "GL shader compile failed: " + log
                );
            }

            return shader;
        }

        void release() {
            released = true;

            if (display != EGL14.EGL_NO_DISPLAY) {
                // GL resources must be deleted while the EGL context is still
                // current. Only detach the context after the deletions.
                try {
                    if (compositeTexture != 0) {
                        GLES20.glDeleteTextures(
                                1,
                                new int[]{compositeTexture},
                                0
                        );
                    }
                } catch (Exception ignored) {
                }
                compositeTexture = 0;

                try {
                    if (program != 0) {
                        GLES20.glDeleteProgram(program);
                    }
                } catch (Exception ignored) {
                }
                program = 0;

                try {
                    EGL14.eglMakeCurrent(
                            display,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_CONTEXT
                    );
                } catch (Exception ignored) {
                }

                try {
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(display, eglSurface);
                    }
                } catch (Exception ignored) {
                }

                try {
                    if (context != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(display, context);
                    }
                } catch (Exception ignored) {
                }

                try {
                    EGL14.eglTerminate(display);
                } catch (Exception ignored) {
                }
            }

            display = EGL14.EGL_NO_DISPLAY;
            context = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;
            vertexBuffer = null;
        }
    }

    private static final class Credentials {

        final String ssid;
        final String password;

        Credentials(
                String ssid,
                String password
        ) {

            this.ssid =
                    ssid;

            this.password =
                    password;
        }
    }

    public static final class CameraEndpoint {

        final String host;
        final int port;
        final String scheme;
        final String apiUrl;

        /*
         * Internal metadata only.
         * Never sent to Flutter.
         */
        final String friendlyName;
        final String modelName;

        CameraEndpoint(
                String host,
                int port,
                String scheme,
                String apiUrl,
                String friendlyName,
                String modelName
        ) {

            this.host =
                    host;

            this.port =
                    port;

            this.scheme =
                    scheme == null
                            || scheme.isEmpty()
                            ? "http"
                            : scheme;

            this.apiUrl =
                    apiUrl;

            this.friendlyName =
                    friendlyName == null
                            ? ""
                            : friendlyName;

            this.modelName =
                    modelName == null
                            ? ""
                            : modelName;
        }

        @Override
        public String toString() {
            return apiUrl;
        }
    }


}
