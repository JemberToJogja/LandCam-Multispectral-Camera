package com.example.landcam;

import android.Manifest;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiManager;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

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

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * LANDCAM native camera transport.
 *
 * Single-file design: no additional Java classes/files are required.
 *
 * Connection strategy:
 *   NFC -> Wi-Fi -> SSDP discovery -> device description XML -> Camera API
 *   fallback -> network-derived host/port probing -> Camera API
 *
 * The transport is intentionally not locked to a single host or port.
 *
 * Internal camera protocol details may still depend on the connected device,
 * but device brand/model identity is intentionally not exposed to Flutter UI.
 */
public class MainActivity extends FlutterActivity {

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
            55L;

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

    private EventChannel.EventSink eventSink;

    private final List<HashMap<String, Object>> pendingEvents =
            Collections.synchronizedList(
                    new ArrayList<>()
            );

    private NfcAdapter nfcAdapter;

    private PendingIntent pendingIntent;

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

    private volatile long lastFrameEventAt =
            0L;

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

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(savedInstanceState);

        setupFullscreen();
        setupNfc();
        checkPermissions();
        handleNfcIntent(getIntent());
    }

    @Override
    public void configureFlutterEngine(
            FlutterEngine flutterEngine
    ) {
        super.configureFlutterEngine(
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
                        eventSink = events;

                        sendEventNow(
                                "ready",
                                null
                        );

                        flushPendingEvents();
                    }

                    @Override
                    public void onCancel(
                            Object arguments
                    ) {
                        eventSink = null;
                    }
                }
        );
    }

    private void handleMethodCall(
            MethodCall call,
            MethodChannel.Result result
    ) {
        try {
            switch (call.method) {

                case "initialize":
                    result.success(true);
                    return;

                case "startNfc":

                    if (nfcAdapter == null) {
                        sendEvent(
                                "nfcUnavailable",
                                "NFC NOT SUPPORTED"
                        );
                        result.success(false);
                        return;
                    }

                    if (!nfcAdapter.isEnabled()) {
                        sendEvent(
                                "nfcUnavailable",
                                "NFC IS DISABLED"
                        );
                        result.success(false);
                        return;
                    }

                    updateSystemStatus(
                            "WAITING FOR NFC",
                            true
                    );

                    result.success(true);
                    return;

                case "stopNfc":

                    updateSystemStatus(
                            "NFC READY",
                            true
                    );

                    result.success(true);
                    return;

                case "connectLastWifi":

                    if (lastSsid == null
                            || lastSsid.trim().isEmpty()) {

                        sendEvent(
                                "networkUnavailable",
                                "NO SAVED CAMERA NETWORK"
                        );

                        result.success(false);
                        return;
                    }

                    connectWifi(
                            lastSsid,
                            lastPassword
                    );

                    result.success(true);
                    return;

                case "probeCurrentNetwork":

                    probeCurrentNetwork();

                    result.success(true);
                    return;

                case "refreshLiveview":

                    refreshLiveview();

                    result.success(true);
                    return;

                case "capture":

                    takePicture();

                    result.success(true);
                    return;

                case "autofocus":

                    triggerAutoFocus();

                    result.success(true);
                    return;

                case "toggleViewMode":

                    toggleViewMode();

                    result.success(
                            isQuadMode
                    );

                    return;

                case "setGrayscale":

                    grayscaleMode =
                            call.arguments instanceof Boolean
                                    && (Boolean) call.arguments;

                    sendEvent(
                            "grayscaleChanged",
                            grayscaleMode
                    );

                    result.success(true);
                    return;

                case "setSpectralBand":

                    handleSpectralBand(
                            call.arguments,
                            result
                    );

                    return;

                case "disconnect":

                    disconnectCamera();

                    result.success(true);
                    return;

                default:

                    result.notImplemented();
            }

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Method call failed: "
                            + call.method,
                    e
            );

            result.error(
                    "NATIVE_ERROR",
                    safeMessage(e),
                    null
            );
        }
    }

    private void handleSpectralBand(
            Object rawBand,
            MethodChannel.Result result
    ) {

        String band =
                rawBand == null
                        ? ""
                        : rawBand.toString()
                                .trim()
                                .toUpperCase(Locale.US);

        if (!Arrays.asList("RGB", "R", "G", "B", "NIR").contains(band)) {
            result.success(false);
            return;
        }

        if ("NIR".equals(band) && !realNirAvailable) {
            sendEvent(
                    "engineWarning",
                    "NIR IS NOT AVAILABLE: RIGHT OPTICAL ROI WAS NOT DETECTED"
            );
            result.success(false);
            return;
        }

        activeSpectralBand = band;
        spectralGeneration.incrementAndGet();

        HashMap<String, Object> data = new HashMap<>();
        data.put("band", band);
        data.put(
                "source",
                "NIR".equals(band)
                        ? "NIR_RIGHT_OPTICAL_ROI"
                        : "RGB_LEFT_OPTICAL_ROI"
        );
        data.put("realSpectralFrame", true);

        sendEvent("spectralBandChanged", data);

        updateSystemStatus(
                "NIR".equals(band)
                        ? "NIR VIEW"
                        : band + " VIEW",
                true
        );

        result.success(true);
    }

    private void setupFullscreen() {

        getWindow().setFlags(
                android.view.WindowManager
                        .LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager
                        .LayoutParams.FLAG_FULLSCREEN
        );

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.R) {

            getWindow()
                    .setDecorFitsSystemWindows(
                            false
                    );

            if (getWindow()
                    .getInsetsController() != null) {

                getWindow()
                        .getInsetsController()
                        .hide(
                                android.view.WindowInsets
                                        .Type.statusBars()
                                        | android.view.WindowInsets
                                        .Type.navigationBars()
                        );
            }

        } else {

            getWindow()
                    .getDecorView()
                    .setSystemUiVisibility(
                            android.view.View
                                    .SYSTEM_UI_FLAG_FULLSCREEN
                                    | android.view.View
                                    .SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | android.view.View
                                    .SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    | android.view.View
                                    .SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | android.view.View
                                    .SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | android.view.View
                                    .SYSTEM_UI_FLAG_LAYOUT_STABLE
                    );
        }
    }

    private void checkPermissions() {

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.TIRAMISU) {

            if (checkSelfPermission(
                    Manifest.permission
                            .NEARBY_WIFI_DEVICES
            ) != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(
                        new String[]{
                                Manifest.permission
                                        .NEARBY_WIFI_DEVICES
                        },
                        LOCATION_PERMISSION_REQUEST
                );
            }

            return;
        }

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.M
                && checkSelfPermission(
                Manifest.permission
                        .ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{
                            Manifest.permission
                                    .ACCESS_FINE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST
            );
        }
    }

    private boolean hasWifiPermission() {

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.TIRAMISU) {

            return checkSelfPermission(
                    Manifest.permission
                            .NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED;
        }

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.M) {

            return checkSelfPermission(
                    Manifest.permission
                            .ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }

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
                ).addFlags(
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                );

        int flags =
                PendingIntent.FLAG_UPDATE_CURRENT;

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.S) {

            flags |=
                    PendingIntent.FLAG_MUTABLE;
        }

        pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        flags
                );
    }

    @Override
    protected void onResume() {

        super.onResume();

        if (nfcAdapter != null
                && pendingIntent != null) {

            try {

                nfcAdapter.enableForegroundDispatch(
                        this,
                        pendingIntent,
                        null,
                        null
                );

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
    }

    @Override
    protected void onPause() {

        if (nfcAdapter != null) {

            try {
                nfcAdapter.disableForegroundDispatch(
                        this
                );
            } catch (Exception ignored) {
            }
        }

        super.onPause();
    }

    @Override
    protected void onNewIntent(
            Intent intent
    ) {
        super.onNewIntent(intent);

        setIntent(intent);

        handleNfcIntent(intent);
    }

    private void handleNfcIntent(
            Intent intent
    ) {
        if (intent == null) return;

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

            rawMessages = null;
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

        try {

            Credentials credentials =
                    null;

            for (
                    android.os.Parcelable raw
                    : rawMessages
            ) {

                if (!(raw instanceof NdefMessage)) {
                    continue;
                }

                NdefMessage message =
                        (NdefMessage) raw;

                for (
                        NdefRecord record
                        : message.getRecords()
                ) {

                    credentials =
                            parseSonyCredentials(
                                    record.getPayload()
                            );

                    if (credentials != null) {
                        break;
                    }
                }

                if (credentials != null) {
                    break;
                }
            }

            if (credentials == null) {

                sendEvent(
                        "error",
                        "NFC RECEIVED BUT CAMERA "
                                + "WI-FI CREDENTIALS NOT FOUND"
                );

                return;
            }

            lastSsid =
                    credentials.ssid;

            lastPassword =
                    credentials.password;

            HashMap<String, Object> event =
                    new HashMap<>();

            event.put(
                    "ssid",
                    lastSsid
            );

            event.put(
                    "payloadDetected",
                    true
            );

            sendEvent(
                    "nfcDetected",
                    event
            );

            log(
                    "INFO",
                    "Camera network detected: "
                            + lastSsid
            );

            connectWifi(
                    lastSsid,
                    lastPassword
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "NFC parsing error",
                    e
            );

            sendEvent(
                    "error",
                    "NFC PARSE FAILED: "
                            + safeMessage(e)
            );
        }
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
                        getSystemService(
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

                        if (isFinishing()
                                || isDestroyed()) {
                            return;
                        }

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

    private void probeCurrentNetwork() {

        ConnectivityManager cm =
                (ConnectivityManager)
                        getSystemService(
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
        data.put("spectralSource", "DUAL OPTICAL FOV COMPOSITE");
        data.put("realNirAvailable", realNirAvailable);
        data.put("rawBayerStreamAvailable", false);
        data.put(
                "nirSource",
                realNirAvailable
                        ? "RIGHT OPTICAL ROI"
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
        data.put("rgbSource", "LEFT OPTICAL ROI");

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

        isStreaming.set(true);
        firstFrameSent = false;
        lastFrameEventAt = 0L;
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

            processAndEmitCompositeFrame(jpeg);

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

    private void processAndEmitCompositeFrame(byte[] compositeJpeg) {

        if (compositeJpeg == null || compositeJpeg.length == 0) return;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap source = BitmapFactory.decodeByteArray(
                compositeJpeg,
                0,
                compositeJpeg.length,
                options
        );

        if (source == null) {
            log("WARN", "Composite frame decode failed");
            return;
        }

        try {
            if (source.getWidth() > MAX_PROCESSING_DIMENSION
                    || source.getHeight() > MAX_PROCESSING_DIMENSION) {
                log("WARN", "Composite frame exceeds processing dimension");
                return;
            }

            ensureDualOpticalCalibration(source);
            lastCompositeFrameBytes = compositeJpeg;

            final String band = activeSpectralBand == null
                    ? "RGB"
                    : activeSpectralBand;
            final long generation = spectralGeneration.get();

            byte[] processed = processCompositeForBand(source, band);
            if (processed == null || processed.length == 0) return;

            if (generation != spectralGeneration.get()) return;
            if (!band.equals(activeSpectralBand)) return;

            lastFrameBytes = processed;

            emitProcessedFrame(
                    processed,
                    band,
                    "NIR".equals(band)
                            ? "NIR_RIGHT_OPTICAL_ROI"
                            : "RGB_LEFT_OPTICAL_ROI",
                    true
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

        log(
                "INFO",
                "Dual optical square calibration: RGB="
                        + rectToString(rgbCropRect)
                        + " NIR="
                        + rectToString(nirCropRect)
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

    private byte[] processCompositeForBand(
            Bitmap source,
            String band
    ) {

        Rect rect = "NIR".equals(band)
                ? nirCropRect
                : rgbCropRect;

        if (!isUsableCrop(rect, source.getWidth(), source.getHeight())) {
            return null;
        }

        Bitmap crop = Bitmap.createBitmap(
                source,
                rect.left,
                rect.top,
                rect.width(),
                rect.height()
        );

        Bitmap result = crop;
        try {
            if ("R".equals(band)) {
                result = channelBitmap(crop, 0);
            } else if ("G".equals(band)) {
                result = channelBitmap(crop, 1);
            } else if ("B".equals(band)) {
                result = channelBitmap(crop, 2);
            } else if ("NIR".equals(band)) {
                result = grayscaleBitmap(crop);
            }

            return bitmapToJpeg(result, IMAGE_JPEG_QUALITY);
        } finally {
            if (result != crop && result != null && !result.isRecycled()) {
                result.recycle();
            }
            if (!crop.isRecycled()) {
                crop.recycle();
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

    private void emitProcessedFrame(
            byte[] jpeg,
            String band,
            String source,
            boolean throttle
    ) {
        if (jpeg == null || jpeg.length == 0) return;

        if (throttle) {
            long now = System.currentTimeMillis();
            if (now - lastFrameEventAt < FRAME_EVENT_INTERVAL_MS) return;
            lastFrameEventAt = now;
        }

        Rect rect = "NIR".equals(band) ? nirCropRect : rgbCropRect;
        HashMap<String, Object> event = new HashMap<>();
        event.put("bytes", jpeg);
        event.put("band", band);
        event.put("quad", isQuadMode);
        event.put("grayscale", grayscaleMode);
        event.put("source", source);
        event.put("processed", true);
        event.put("isQuadFrame", !throttle);
        event.put("realSpectralFrame", true);
        event.put("width", rect == null ? 0 : rect.width());
        event.put("height", rect == null ? 0 : rect.height());
        event.put("bitDepth", 8);
        event.put("compositeWidth", calibratedSourceWidth);
        event.put("compositeHeight", calibratedSourceHeight);
        event.put("cropX", rect == null ? 0 : rect.left);
        event.put("cropY", rect == null ? 0 : rect.top);
        event.put("cropWidth", rect == null ? 0 : rect.width());
        event.put("cropHeight", rect == null ? 0 : rect.height());

        sendEvent("liveviewFrame", event);

        if (!firstFrameSent) {
            firstFrameSent = true;
            sendEvent("firstLiveviewFrame", true);
            updateSystemStatus("CAMERA READY", true);
        }
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
        emitProcessedFrame(jpeg, band, source, false);
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

    private void refreshLiveview() {

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

                        probeCurrentNetwork();
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
            Network network = currentNetwork;
            CameraEndpoint endpoint = currentEndpoint();
            byte[] fallbackFrame = lastCompositeFrameBytes;

            if (network == null || endpoint == null) {
                sendEvent("captureError", "CAMERA NOT READY");
                return;
            }

            try {
                JSONObject response = callApiAt(
                        network,
                        endpoint,
                        "actTakePicture",
                        new JSONArray(),
                        API_CONNECT_TIMEOUT_MS,
                        API_READ_TIMEOUT_MS
                );

                if (hasApiError(response)) {
                    throw new IllegalStateException(
                            "SHUTTER FAILED: " + apiErrorDescription(response)
                    );
                }

                String imageUrl = findFirstImageUrl(response);
                byte[] composite = null;

                if (imageUrl != null && !imageUrl.isEmpty()) {
                    try {
                        composite = downloadBytes(
                                network,
                                normalizeStreamUrl(imageUrl, endpoint),
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

                if (composite == null || composite.length == 0) {
                    composite = fallbackFrame;
                }

                if (composite == null || composite.length == 0) {
                    throw new IllegalStateException("NO CAPTURE IMAGE AVAILABLE");
                }

                Bitmap source = BitmapFactory.decodeByteArray(
                        composite,
                        0,
                        composite.length
                );

                if (source == null) {
                    throw new IllegalStateException("CAPTURE IMAGE DECODE FAILED");
                }

                String band = activeSpectralBand == null
                        ? "RGB"
                        : activeSpectralBand;

                try {
                    ensureDualOpticalCalibration(source);
                    byte[] processed = processCompositeForBand(source, band);
                    if (processed == null || processed.length == 0) {
                        throw new IllegalStateException(
                                "FAILED TO PROCESS " + band + " OPTICAL ROI"
                        );
                    }

                    String fileName = saveToGallery(processed, band);

                    HashMap<String, Object> data = new HashMap<>();
                    data.put("fileName", fileName);
                    data.put("band", band);
                    data.put("source",
                            "NIR".equals(band)
                                    ? "NIR_RIGHT_OPTICAL_ROI"
                                    : "RGB_LEFT_OPTICAL_ROI");
                    data.put("processed", true);
                    data.put("width",
                            ("NIR".equals(band) ? nirCropRect : rgbCropRect).width());
                    data.put("height",
                            ("NIR".equals(band) ? nirCropRect : rgbCropRect).height());

                    sendEvent("captureSaved", data);
                    sendEvent("shutterAck", data);

                } finally {
                    source.recycle();
                }

            } catch (Exception e) {
                Log.e(TAG, "Capture failed", e);
                sendEvent(
                        "captureError",
                        "CAPTURE FAILED: " + safeMessage(e)
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
                getContentResolver();

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

    private void triggerAutoFocus() {

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
                        "AF-S",
                        "AF-C",
                        "Continuous AF"
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

    private void toggleViewMode() {

        isQuadMode =
                !isQuadMode;

        HashMap<String, Object> data =
                new HashMap<>();

        data.put(
                "quad",
                isQuadMode
        );

        sendEvent(
                "viewModeChanged",
                data
        );
    }

    private void disconnectCamera() {

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
        calibratedSourceWidth = -1;
        calibratedSourceHeight = -1;
        rgbCropRect = null;
        nirCropRect = null;
        lastQuadEventAt = 0L;
        spectralGeneration.incrementAndGet();
    }

    private void stopStreaming() {

        isStreaming.set(
                false
        );

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
                            getApplicationContext()
                                    .getSystemService(
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
                        "AF-S",
                        "Continuous AF"
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

    private void sendEvent(
            String type,
            Object data
    ) {

        if (type == null) {
            return;
        }

        if (
                "liveviewFrame"
                        .equals(type)
        ) {

            sendEventNow(
                    type,
                    data
            );

            return;
        }

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

        if (eventSink == null) {

            pendingEvents.add(
                    event
            );

            while (
                    pendingEvents.size()
                            > 256
            ) {

                pendingEvents.remove(0);
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

        EventChannel.EventSink sink =
                eventSink;

        if (sink == null) {
            return;
        }

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

        runOnUiThread(
                () -> {

                    EventChannel.EventSink current =
                            eventSink;

                    if (current != null) {

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
                }
        );
    }

    private void flushPendingEvents() {

        List<HashMap<String, Object>>
                snapshot;

        synchronized (
                pendingEvents
        ) {

            snapshot =
                    new ArrayList<>(
                            pendingEvents
                    );

            pendingEvents.clear();
        }

        for (
                HashMap<String, Object> event
                : snapshot
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
                            : type.toString(),
                    data
            );
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

    private static final class CameraEndpoint {

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

    @Override
    protected void onDestroy() {

        disconnectCamera();

        executor.shutdownNow();

        discoveryExecutor.shutdownNow();

        super.onDestroy();
    }
}