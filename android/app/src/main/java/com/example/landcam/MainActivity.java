package com.example.landcam;

import android.Manifest;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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

    private final AtomicBoolean isEngineStarting =
            new AtomicBoolean(false);

    private final AtomicBoolean isDiscoveryRunning =
            new AtomicBoolean(false);

    private final AtomicLong sessionGeneration =
            new AtomicLong(0L);

    private final AtomicReference<CameraEndpoint> discoveredEndpoint =
            new AtomicReference<>(null);

    private volatile byte[] lastFrameBytes;

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
                        : rawBand
                                .toString()
                                .trim()
                                .toUpperCase(
                                        Locale.US
                                );

        if (SOFTWARE_DISPLAY_BANDS.contains(
                band
        )) {

            HashMap<String, Object> data =
                    new HashMap<>();

            data.put(
                    "band",
                    band
            );

            data.put(
                    "source",
                    "RGB_LIVEVIEW_CHANNEL"
            );

            data.put(
                    "realSpectralFrame",
                    false
            );

            sendEvent(
                    "spectralBandChanged",
                    data
            );

            result.success(true);
            return;
        }

        if ("NIR".equals(band)) {

            sendEvent(
                    "engineWarning",
                    "NIR SENSOR DATA IS NOT PROVIDED "
                            + "BY THE CURRENT CAMERA PIPELINE"
            );

            result.success(false);
            return;
        }

        result.success(false);
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
                                lastLiveviewUrl
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

        HashMap<String, Object> data =
                new HashMap<>();

        data.put(
                "bands",
                SOFTWARE_DISPLAY_BANDS
        );

        data.put(
                "spectralSource",
                "RGB LIVEVIEW"
        );

        data.put(
                "realNirAvailable",
                false
        );

        data.put(
                "rawBayerStreamAvailable",
                false
        );

        /*
         * Generic protocol only.
         */
        data.put(
                "protocol",
                UI_PROTOCOL_LABEL
        );

        data.put(
                "liveView",
                supports(
                        apiMethods,
                        "startLiveview"
                )
        );

        data.put(
                "capture",
                supports(
                        apiMethods,
                        "actTakePicture"
                )
        );

        data.put(
                "autofocus",
                supports(
                        apiMethods,
                        "actFocus"
                )
        );

        sendEvent(
                "cameraCapabilities",
                data
        );
    }

    private boolean supports(
            Set<String> methods,
            String method
    ) {

        return methods == null
                || methods.isEmpty()
                || methods.contains(method);
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
            String url
    ) {

        stopStreaming();

        isStreaming.set(
                true
        );

        firstFrameSent =
                false;

        lastFrameEventAt =
                0L;

        updateSystemStatus(
                "LIVE VIEW ACTIVE",
                true
        );

        sendEvent(
                "liveviewActive",
                true
        );

        log(
                "INFO",
                "Live View stream started"
        );

        executor.execute(
                () -> {

                    HttpURLConnection connection =
                            null;

                    try {

                        URL streamUrl =
                                new URL(
                                        url
                                );

                        Network network =
                                currentNetwork;

                        if (network == null) {

                            throw new IllegalStateException(
                                    "NO CAMERA NETWORK "
                                            + "FOR LIVE VIEW"
                            );
                        }

                        URLConnection raw =
                                network.openConnection(
                                        streamUrl
                                );

                        if (!(raw
                                instanceof HttpURLConnection)) {

                            throw new IllegalStateException(
                                    "LIVE VIEW IS NOT HTTP"
                            );
                        }

                        connection =
                                (HttpURLConnection)
                                        raw;

                        liveviewConnection =
                                connection;

                        connection.setConnectTimeout(
                                LIVEVIEW_CONNECT_TIMEOUT_MS
                        );

                        connection.setReadTimeout(
                                0
                        );

                        connection.setUseCaches(
                                false
                        );

                        connection.setRequestProperty(
                                "Accept",
                                "image/jpeg, "
                                        + "multipart/x-mixed-replace, */*"
                        );

                        int responseCode =
                                connection.getResponseCode();

                        if (
                                responseCode < 200
                                        || responseCode >= 400
                        ) {

                            throw new IllegalStateException(
                                    "LIVE VIEW HTTP "
                                            + responseCode
                            );
                        }

                        try (
                                InputStream input =
                                        new BufferedInputStream(
                                                connection
                                                        .getInputStream()
                                        )
                        ) {

                            ByteArrayOutputStream accumulator =
                                    new ByteArrayOutputStream();

                            byte[] buffer =
                                    new byte[8192];

                            while (
                                    isStreaming.get()
                            ) {

                                int count =
                                        input.read(
                                                buffer
                                        );

                                if (count < 0) {
                                    break;
                                }

                                if (count == 0) {
                                    continue;
                                }

                                accumulator.write(
                                        buffer,
                                        0,
                                        count
                                );

                                extractJpegFrames(
                                        accumulator
                                );

                                if (
                                        accumulator.size()
                                                > 5 * 1024 * 1024
                                ) {

                                    trimStreamBuffer(
                                            accumulator
                                    );
                                }
                            }
                        }

                        if (
                                isStreaming.get()
                        ) {

                            isStreaming.set(
                                    false
                            );

                            updateSystemStatus(
                                    "LIVE VIEW DISCONNECTED",
                                    false
                            );

                            sendEvent(
                                    "streamLost",
                                    "LIVE VIEW DISCONNECTED"
                            );
                        }

                    } catch (Exception e) {

                        if (
                                isStreaming.get()
                        ) {

                            isStreaming.set(
                                    false
                            );

                            Log.e(
                                    TAG,
                                    "Live View stream failed",
                                    e
                            );

                            updateSystemStatus(
                                    "LIVE VIEW DISCONNECTED",
                                    false
                            );

                            sendEvent(
                                    "streamLost",
                                    "LIVE VIEW DISCONNECTED: "
                                            + safeMessage(e)
                            );

                            log(
                                    "ERROR",
                                    "Live View failed: "
                                            + safeMessage(e)
                            );
                        }

                    } finally {

                        if (connection != null) {
                            connection.disconnect();
                        }

                        if (
                                liveviewConnection
                                        == connection
                        ) {

                            liveviewConnection =
                                    null;
                        }
                    }
                }
        );
    }

    private void extractJpegFrames(
            ByteArrayOutputStream accumulator
    ) {

        while (
                isStreaming.get()
        ) {

            byte[] data =
                    accumulator.toByteArray();

            int start =
                    findSeq(
                            data,
                            new byte[]{
                                    (byte) 0xFF,
                                    (byte) 0xD8
                            },
                            0
                    );

            if (start < 0) {

                if (
                        data.length
                                > 65536
                ) {

                    accumulator.reset();

                    accumulator.write(
                            data,
                            data.length
                                    - 65536,
                            65536
                    );
                }

                return;
            }

            int end =
                    findSeq(
                            data,
                            new byte[]{
                                    (byte) 0xFF,
                                    (byte) 0xD9
                            },
                            start + 2
                    );

            if (end < 0) {

                if (start > 0) {

                    accumulator.reset();

                    accumulator.write(
                            data,
                            start,
                            data.length - start
                    );
                }

                return;
            }

            int jpegEnd =
                    end + 2;

            byte[] jpeg =
                    new byte[
                            jpegEnd - start
                    ];

            System.arraycopy(
                    data,
                    start,
                    jpeg,
                    0,
                    jpeg.length
            );

            lastFrameBytes =
                    jpeg;

            long now =
                    System.currentTimeMillis();

            if (
                    now - lastFrameEventAt
                            >= FRAME_EVENT_INTERVAL_MS
            ) {

                lastFrameEventAt =
                        now;

                HashMap<String, Object> event =
                        new HashMap<>();

                event.put(
                        "bytes",
                        jpeg
                );

                event.put(
                        "band",
                        "RGB"
                );

                event.put(
                        "quad",
                        isQuadMode
                );

                event.put(
                        "grayscale",
                        grayscaleMode
                );

                event.put(
                        "source",
                        "LIVEVIEW_JPEG"
                );

                sendEvent(
                        "liveviewFrame",
                        event
                );
            }

            if (!firstFrameSent) {

                firstFrameSent =
                        true;

                sendEvent(
                        "firstLiveviewFrame",
                        true
                );

                updateSystemStatus(
                        "CAMERA READY",
                        true
                );
            }

            byte[] remaining =
                    new byte[
                            data.length
                                    - jpegEnd
                    ];

            System.arraycopy(
                    data,
                    jpegEnd,
                    remaining,
                    0,
                    remaining.length
            );

            accumulator.reset();

            accumulator.write(
                    remaining,
                    0,
                    remaining.length
            );
        }
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

        executor.execute(
                () -> {

                    Network network =
                            currentNetwork;

                    CameraEndpoint endpoint =
                            currentEndpoint();

                    byte[] fallbackFrame =
                            lastFrameBytes;

                    if (
                            network == null
                                    || endpoint == null
                    ) {

                        sendEvent(
                                "captureError",
                                "CAMERA NOT READY"
                        );

                        return;
                    }

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

                        if (
                                hasApiError(
                                        response
                                )
                        ) {

                            throw new IllegalStateException(
                                    "SHUTTER FAILED: "
                                            + apiErrorDescription(
                                            response
                                    )
                            );
                        }

                        String imageUrl =
                                findFirstImageUrl(
                                        response
                                );

                        String fileName =
                                null;

                        if (
                                imageUrl != null
                                        && !imageUrl.isEmpty()
                        ) {

                            try {

                                byte[] captured =
                                        downloadBytes(
                                                network,
                                                normalizeStreamUrl(
                                                        imageUrl,
                                                        endpoint
                                                ),
                                                15000
                                        );

                                if (
                                        captured != null
                                                && captured.length > 0
                                ) {

                                    fileName =
                                            saveToGallery(
                                                    captured
                                            );
                                }

                            } catch (Exception e) {

                                log(
                                        "WARN",
                                        "Camera capture URL download failed: "
                                                + safeMessage(e)
                                );
                            }
                        }

                        if (
                                fileName == null
                                        && fallbackFrame != null
                                        && fallbackFrame.length > 0
                        ) {

                            fileName =
                                    saveToGallery(
                                            fallbackFrame
                                    );
                        }

                        if (fileName == null) {

                            throw new IllegalStateException(
                                    "NO CAPTURE IMAGE AVAILABLE"
                            );
                        }

                        sendEvent(
                                "captureSaved",
                                fileName
                        );

                        sendEvent(
                                "shutterAck",
                                fileName
                        );

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
                }
        );
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
            byte[] jpeg
    ) throws Exception {

        String timestamp =
                new SimpleDateFormat(
                        "yyyyMMdd_HHmmss_SSS",
                        Locale.US
                ).format(
                        new Date()
                );

        String displayName =
                "LandCam_"
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

                        JSONObject response =
                                callApiAt(
                                        network,
                                        endpoint,
                                        "actFocus",
                                        new JSONArray(),
                                        API_CONNECT_TIMEOUT_MS,
                                        API_READ_TIMEOUT_MS
                                );

                        if (
                                hasApiError(
                                        response
                                )
                        ) {

                            sendEvent(
                                    "engineWarning",
                                    "AUTOFOCUS NOT SUPPORTED "
                                            + "BY CAMERA: "
                                            + apiErrorDescription(
                                            response
                                    )
                            );

                            return;
                        }

                        sleepQuietly(
                                500L
                        );

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

            if (
                    text.matches(
                            "[A-Za-z_][A-Za-z0-9]*"
                    )
            ) {

                target.add(
                        text
                );
            }

            return;
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
                    (JSONObject)
                            value;

            java.util.Iterator<String>
                    keys =
                    object.keys();

            while (
                    keys.hasNext()
            ) {

                String key =
                        keys.next();

                collectStringValues(
                        object.opt(key),
                        target,
                        depth + 1
                );
            }
        }
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