package com.example.landcam;

import android.Manifest;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * LANDCAM native transport.
 *
 * Pipeline:
 * NFC foreground dispatch
 *   -> Sony NDEF DIRECT- credentials
 *   -> WifiNetworkSpecifier
 *   -> ConnectivityManager.requestNetwork()
 *   -> bindProcessToNetwork()
 *   -> Sony Camera Remote API
 *   -> Live View JPEG stream
 *   -> capture/save
 *
 * This build deliberately logs every important state transition. Logs are also
 * forwarded to Flutter as `log` events so the connection sheet can show them.
 */
public class MainActivity extends FlutterActivity {
    private static final String TAG = "LandCamMonitor";
    private static final String METHOD_CHANNEL = "landcam/native";
    private static final String EVENT_CHANNEL = "landcam/events";

    private static final String API_URL = "http://192.168.122.1:8080/sony/camera";
    private static final String CAMERA_IP = "192.168.122.1";
    private static final int CAMERA_PORT = 8080;
    private static final int NETWORK_TIMEOUT_MS = 30_000;
    private static final int HTTP_CONNECT_TIMEOUT_MS = 8_000;
    private static final int HTTP_READ_TIMEOUT_MS = 12_000;
    private static final long FRAME_EVENT_INTERVAL_MS = 55L;

    private static final int WIFI_PERMISSION_REQUEST = 3101;

    private EventChannel.EventSink eventSink;
    private final ArrayList<HashMap<String, Object>> pendingLogEvents = new ArrayList<>();
    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Network currentNetwork;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);

    private volatile byte[] lastFrameBytes;
    private volatile boolean firstFrameSent = false;
    private volatile boolean isQuadMode = false;
    private volatile boolean grayscaleMode = false;
    private volatile long lastFrameEventAt = 0L;

    private volatile String lastSsid;
    private volatile String lastPassword;
    private volatile String lastLiveviewUrl;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupFullscreen();
        setupNfc();
        checkWifiPermissions();

        Log.i(TAG, "============================================================");
        Log.i(TAG, "LANDCAM native transport starting");
        Log.i(TAG, "Package: com.example.landcam");
        Log.i(TAG, "API: " + API_URL);
        Log.i(TAG, "Device Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        Log.i(TAG, "============================================================");

        handleIntent("onCreate", getIntent());
    }

    @Override
    public void configureFlutterEngine(FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);

        new MethodChannel(
                flutterEngine.getDartExecutor().getBinaryMessenger(),
                METHOD_CHANNEL
        ).setMethodCallHandler(this::handleMethodCall);

        new EventChannel(
                flutterEngine.getDartExecutor().getBinaryMessenger(),
                EVENT_CHANNEL
        ).setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                eventSink = events;
                flushPendingLogs();
                sendEvent("ready", null);
                logI("Flutter event channel connected");
            }

            @Override
            public void onCancel(Object arguments) {
                logI("Flutter event channel disconnected");
                eventSink = null;
            }
        });
    }

    private void handleMethodCall(MethodCall call, MethodChannel.Result result) {
        logI("MethodChannel <- " + call.method);

        switch (call.method) {
            case "initialize":
                logI("initialize: NFC=" + (nfcAdapter != null) + ", WiFi API=" + Build.VERSION.SDK_INT);
                result.success(true);
                return;

            case "startNfc":
                result.success(startNfcInternal());
                return;

            case "stopNfc":
                stopNfcInternal();
                result.success(true);
                return;

            case "connectLastWifi":
                boolean reconnectStarted = connectWifi(lastSsid, lastPassword, "DART_RECONNECT");
                result.success(reconnectStarted);
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
                isQuadMode = !isQuadMode;
                HashMap<String, Object> mode = new HashMap<>();
                mode.put("quad", isQuadMode);
                sendEvent("viewModeChanged", mode);
                logI("View mode -> " + (isQuadMode ? "QUAD" : "FULL"));
                result.success(isQuadMode);
                return;

            case "setGrayscale":
                boolean enabled = false;
                if (call.arguments instanceof Boolean) {
                    enabled = (Boolean) call.arguments;
                }
                grayscaleMode = enabled;
                sendEvent("grayscaleChanged", enabled);
                logI("Grayscale -> " + enabled);
                result.success(true);
                return;

            case "disconnect":
                disconnectCamera();
                result.success(true);
                return;

            default:
                logW("Unknown MethodChannel call: " + call.method);
                result.notImplemented();
        }
    }

    private void setupFullscreen() {
        getWindow().setFlags(
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            if (getWindow().getInsetsController() != null) {
                getWindow().getInsetsController().hide(
                        android.view.WindowInsets.Type.statusBars()
                                | android.view.WindowInsets.Type.navigationBars()
                );
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private void checkWifiPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                logW("NEARBY_WIFI_DEVICES not granted -> requesting runtime permission");
                requestPermissions(
                        new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},
                        WIFI_PERMISSION_REQUEST
                );
            } else {
                logI("NEARBY_WIFI_DEVICES permission already granted");
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                logW("ACCESS_FINE_LOCATION not granted -> requesting runtime permission");
                requestPermissions(
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        WIFI_PERMISSION_REQUEST
                );
            } else {
                logI("ACCESS_FINE_LOCATION permission already granted");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != WIFI_PERMISSION_REQUEST) return;

        if (grantResults.length == 0) {
            logW("Runtime Wi-Fi permission dialog returned no result");
            return;
        }

        boolean granted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
        logI("Runtime Wi-Fi permission result -> " + (granted ? "GRANTED" : "DENIED"));
        sendEvent("permissionResult", granted);
    }

    private void setupNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        if (nfcAdapter == null) {
            logE("NFC adapter is NULL: device does not expose NFC");
            sendEvent("nfcUnavailable", "NFC NOT SUPPORTED");
            return;
        }

        logI("NFC adapter found. Enabled=" + nfcAdapter.isEnabled());

        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }

        pendingIntent = PendingIntent.getActivity(
                this,
                7711,
                intent,
                flags
        );
    }

    private boolean startNfcInternal() {
        if (nfcAdapter == null) {
            sendEvent("nfcUnavailable", "NFC NOT SUPPORTED");
            return false;
        }

        if (!nfcAdapter.isEnabled()) {
            logW("NFC is disabled in Android settings");
            sendEvent("nfcUnavailable", "NFC IS DISABLED");
            return false;
        }

        logI("NFC listening armed. Hold Sony camera NFC area near phone.");
        updateSystemStatus("WAITING FOR NFC", true);
        return true;
    }

    private void stopNfcInternal() {
        logI("NFC listening stopped by Flutter");
        updateSystemStatus("NFC STOPPED", false);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (nfcAdapter != null && pendingIntent != null) {
            try {
                nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, null);
                logI("NFC foreground dispatch ENABLED");
            } catch (Exception e) {
                logE("NFC foreground dispatch failed", e);
                sendEvent("error", "NFC FOREGROUND DISPATCH FAILED: " + safeMessage(e));
            }
        }
    }

    @Override
    protected void onPause() {
        if (nfcAdapter != null) {
            try {
                nfcAdapter.disableForegroundDispatch(this);
                logI("NFC foreground dispatch DISABLED");
            } catch (Exception e) {
                logW("NFC foreground dispatch disable warning: " + safeMessage(e));
            }
        }
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent("onNewIntent", intent);
    }

    private void handleIntent(String source, Intent intent) {
        if (intent == null) {
            logW(source + ": intent=null");
            return;
        }

        String action = intent.getAction();
        String type = intent.getType();
        String data = intent.getDataString();

        logI(source + ": action=" + action + ", type=" + type + ", data=" + data);

        if (!NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            return;
        }

        android.os.Parcelable[] rawMessages;
        if (Build.VERSION.SDK_INT >= 33) {
            rawMessages = intent.getParcelableArrayExtra(
                    NfcAdapter.EXTRA_NDEF_MESSAGES,
                    android.os.Parcelable.class
            );
        } else {
            rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
        }

        if (rawMessages == null || rawMessages.length == 0) {
            Tag tag;
            if (Build.VERSION.SDK_INT >= 33) {
                tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag.class);
            } else {
                tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            }
            logW("NFC tag received but EXTRA_NDEF_MESSAGES is empty. tag=" + (tag != null));
            sendEvent("nfcTag", action);
            return;
        }

        boolean sonyRecordSeen = false;
        boolean credentialsAccepted = false;

        for (int m = 0; m < rawMessages.length; m++) {
            try {
                NdefMessage message = (NdefMessage) rawMessages[m];
                NdefRecord[] records = message.getRecords();
                logI("NFC message[" + m + "] records=" + records.length);

                for (int r = 0; r < records.length; r++) {
                    NdefRecord record = records[r];
                    byte[] payload = record.getPayload();
                    byte[] typeBytes = record.getType();
                    String typeText = new String(typeBytes, StandardCharsets.US_ASCII);

                    logI("NFC record[" + r + "] tnf=" + record.getTnf()
                            + " type='" + sanitize(typeText, 100) + "'"
                            + " payloadLen=" + (payload == null ? 0 : payload.length)
                            + " payloadHex=" + bytesToHex(payload, 220));

                    if (record.getTnf() == NdefRecord.TNF_MIME_MEDIA
                            && "application/x-sony-pmm".equalsIgnoreCase(typeText)) {
                        sonyRecordSeen = true;
                        logI("SONY PMM MIME RECORD FOUND");
                        if (parseSonyPmmRecord(record)) {
                            credentialsAccepted = true;
                            break;
                        }
                    }
                }

                if (credentialsAccepted) break;
            } catch (Exception e) {
                logE("Failed reading NFC message " + m, e);
            }
        }

        if (credentialsAccepted) {
            return;
        }

        if (sonyRecordSeen) {
            logE("Sony PMM record was found, but credentials could not be parsed");
            sendEvent("error", "SONY NFC CREDENTIAL PARSE FAILED");
        } else {
            logW("NFC detected, but no application/x-sony-pmm record was found");
            sendEvent("nfcDetected", "NFC TAG DETECTED — NOT SONY PMM");
        }
    }

    /**
     * Parse Sony's application/x-sony-pmm record.
     *
     * The Sony NFC payload is a binary structure. Treating it as a normal UTF-8
     * string and taking a fixed 23-character SSID / last 8 bytes is unreliable.
     * The parser below first tries the known Sony length-prefixed layout and
     * then falls back to locating the ASCII DIRECT- SSID and the following
     * printable password field.
     */
    private boolean parseSonyPmmRecord(NdefRecord record) {
        byte[] payload = record.getPayload();
        if (payload == null || payload.length == 0) {
            logW("Sony PMM record has empty payload");
            return false;
        }

        logI("Sony PMM payloadLen=" + payload.length
                + " hex=" + bytesToHex(payload, Math.min(payload.length, 220)));

        SonyCredentials structured = parseSonyStructured(payload);
        if (structured != null) {
            return publishAndConnect(structured, "NFC_STRUCTURED");
        }

        logW("Sony PMM structured parser did not produce credentials; trying ASCII fallback");
        SonyCredentials fallback = parseSonyAsciiFallback(payload);
        if (fallback != null) {
            return publishAndConnect(fallback, "NFC_ASCII_FALLBACK");
        }

        logE("Sony PMM parser failed: no valid DIRECT- SSID/password pair found");
        return false;
    }

    private SonyCredentials parseSonyStructured(byte[] payload) {
        // Known Sony PPM layout used by the community reverse-engineered clients:
        // byte 8 = SSID length, followed by SSID bytes; after four bytes of
        // metadata, one byte gives password length, followed by password bytes.
        if (payload.length <= 8) return null;

        int ssidLength = unsignedByte(payload[8]);
        int ssidStart = 9;
        int ssidEnd = ssidStart + ssidLength;
        if (ssidLength < 8 || ssidLength > 32 || ssidEnd > payload.length) {
            logW("Structured Sony parser: implausible SSID length=" + ssidLength);
            return null;
        }

        String ssid = ascii(payload, ssidStart, ssidLength).trim();
        if (!ssid.startsWith("DIRECT-")) {
            logW("Structured Sony parser: SSID='" + sanitize(ssid, 80)
                    + "' does not start with DIRECT-");
            return null;
        }

        int passwordLengthOffset = 8 + ssidLength + 4;
        if (passwordLengthOffset >= payload.length) {
            logW("Structured Sony parser: password length offset outside payload");
            return null;
        }

        int passwordLength = unsignedByte(payload[passwordLengthOffset]);
        int passwordStart = passwordLengthOffset + 1;
        int passwordEnd = passwordStart + passwordLength;
        if (passwordLength < 8 || passwordLength > 63 || passwordEnd > payload.length) {
            logW("Structured Sony parser: implausible password length=" + passwordLength);
            return null;
        }

        String password = ascii(payload, passwordStart, passwordLength);
        if (!isPrintableAscii(password) || password.indexOf('\0') >= 0) {
            logW("Structured Sony parser: password bytes are not printable ASCII");
            return null;
        }

        logI("Structured Sony parser SUCCESS: SSID='" + ssid
                + "', passwordLength=" + password.length()
                + ", password=" + maskSecret(password));
        return new SonyCredentials(ssid, password);
    }

    private SonyCredentials parseSonyAsciiFallback(byte[] payload) {
        int direct = indexOfAscii(payload, "DIRECT-");
        if (direct < 0) {
            logW("ASCII fallback: DIRECT- not found");
            return null;
        }

        int ssidEnd = direct;
        while (ssidEnd < payload.length && isPrintable(payload[ssidEnd])) {
            ssidEnd++;
        }
        String ssid = ascii(payload, direct, ssidEnd - direct).trim();
        if (ssid.length() < 8 || ssid.length() > 32) {
            logW("ASCII fallback: invalid SSID length=" + ssid.length());
            return null;
        }

        // Search the next printable run after the binary separator. Sony camera
        // passwords used by the legacy PMM format are normally 8 printable chars.
        int cursor = ssidEnd;
        while (cursor < payload.length) {
            while (cursor < payload.length && !isPrintable(payload[cursor])) cursor++;
            if (cursor >= payload.length) break;

            int runStart = cursor;
            while (cursor < payload.length && isPrintable(payload[cursor])) cursor++;
            int runLength = cursor - runStart;
            if (runLength >= 8 && runLength <= 63) {
                String candidate = ascii(payload, runStart, runLength);
                // Prefer exactly 8 characters, otherwise accept a standard WPA2
                // password-sized printable candidate.
                if (candidate.length() == 8 || runLength == 8) {
                    logI("ASCII fallback SUCCESS: SSID='" + ssid
                            + "', passwordLength=" + candidate.length()
                            + ", password=" + maskSecret(candidate));
                    return new SonyCredentials(ssid, candidate);
                }
            }
        }

        logW("ASCII fallback: no password candidate found after SSID");
        return null;
    }

    private boolean publishAndConnect(SonyCredentials credentials, String source) {
        lastSsid = credentials.ssid;
        lastPassword = credentials.password;

        HashMap<String, Object> event = new HashMap<>();
        event.put("payload", "SONY_PMM");
        event.put("ssid", credentials.ssid);
        event.put("passwordLength", credentials.password.length());
        event.put("parser", source);
        sendEvent("nfcDetected", event);

        logI("Sony NFC credentials accepted by parser=" + source
                + ": SSID='" + credentials.ssid
                + "', passwordLength=" + credentials.password.length());

        return connectWifi(credentials.ssid, credentials.password, source);
    }

    private static final class SonyCredentials {
        final String ssid;
        final String password;

        SonyCredentials(String ssid, String password) {
            this.ssid = ssid;
            this.password = password;
        }
    }

    private static int unsignedByte(byte value) {
        return value & 0xFF;
    }

    private static boolean isPrintable(byte value) {
        int b = value & 0xFF;
        return b >= 0x20 && b <= 0x7E;
    }

    private static boolean isPrintableAscii(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    private static String ascii(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) return "";
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }

    private static int indexOfAscii(byte[] data, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= data.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private boolean connectWifi(String ssid, String password, String source) {
        if (ssid == null || ssid.trim().isEmpty()) {
            sendEvent("networkUnavailable", "NO CAMERA SSID");
            logE("Wi-Fi connect aborted: SSID is empty");
            return false;
        }

        if (password == null || password.length() < 8) {
            sendEvent("networkUnavailable", "INVALID CAMERA WIFI PASSWORD");
            logE("Wi-Fi connect aborted: password length="
                    + (password == null ? "null" : password.length()));
            return false;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            sendEvent("networkUnavailable", "ANDROID 10+ REQUIRED");
            logE("WifiNetworkSpecifier requires Android 10+; current API=" + Build.VERSION.SDK_INT);
            return false;
        }

        if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            logW("Wi-Fi connect requested before NEARBY_WIFI_DEVICES permission was granted");
            sendEvent("networkUnavailable", "NEARBY WIFI PERMISSION NOT GRANTED");
            return false;
        }

        if (networkCallback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
                logI("Old NetworkCallback unregistered before new request");
            } catch (Exception e) {
                logW("Old NetworkCallback unregister warning: " + safeMessage(e));
            }
            networkCallback = null;
        }

        try {
            if (connectivityManager == null) {
                connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            }

            if (connectivityManager == null) {
                logE("ConnectivityManager is NULL");
                sendEvent("networkUnavailable", "CONNECTIVITY SERVICE UNAVAILABLE");
                return false;
            }

            logI("============================================================");
            logI("WIFI REQUEST START source=" + source);
            logI("SSID='" + ssid + "'");
            logI("Password length=" + password.length());
            logI("Target camera=" + CAMERA_IP + ":" + CAMERA_PORT);
            logI("Android API=" + Build.VERSION.SDK_INT);
            logI("Using WifiNetworkSpecifier + no-INTERNET peer network");

            WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build();

            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    // Sony camera AP is intentionally local-only. Request it as a
                    // peer network instead of demanding an internet capability.
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build();

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    super.onAvailable(network);
                    currentNetwork = network;

                    logI("WIFI onAvailable() network=" + network);

                    boolean bound = false;
                    try {
                        bound = connectivityManager.bindProcessToNetwork(network);
                    } catch (Exception e) {
                        logE("bindProcessToNetwork exception", e);
                    }
                    logI("bindProcessToNetwork -> " + bound);

                    logNetworkState(network);
                    updateSystemStatus("WIFI LINK READY", true);
                    sendEvent("wifiConnected", ssid);

                    executor.execute(() -> probeAndStartCamera(network));
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    super.onCapabilitiesChanged(network, capabilities);
                    logI("WIFI onCapabilitiesChanged: " + capabilitiesToString(capabilities));
                    sendEvent("wifiCapabilities", capabilitiesToString(capabilities));
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    super.onLinkPropertiesChanged(network, linkProperties);
                    logI("WIFI onLinkPropertiesChanged: " + linkProperties);
                }

                @Override
                public void onBlockedStatusChanged(Network network, boolean blocked) {
                    super.onBlockedStatusChanged(network, blocked);
                    logW("WIFI onBlockedStatusChanged: blocked=" + blocked);
                }

                @Override
                public void onLost(Network network) {
                    super.onLost(network);
                    logW("WIFI onLost() network=" + network);
                    if (currentNetwork == network) {
                        currentNetwork = null;
                    }
                    isStreaming.set(false);
                    updateSystemStatus("CAMERA NETWORK LOST", false);
                    sendEvent("networkLost", ssid);
                }

                @Override
                public void onUnavailable() {
                    super.onUnavailable();
                    logE("WIFI onUnavailable(): Android did not establish the requested camera AP");
                    updateSystemStatus("CAMERA WIFI UNAVAILABLE", false);
                    sendEvent("networkUnavailable", ssid);
                }
            };

            sendEvent("wifiConnecting", ssid);
            updateSystemStatus("CONNECTING TO CAMERA", true);

            connectivityManager.requestNetwork(
                    request,
                    networkCallback,
                    NETWORK_TIMEOUT_MS
            );

            logI("ConnectivityManager.requestNetwork() submitted successfully");
            logI("WAITING FOR ANDROID WIFI USER APPROVAL / NETWORK CALLBACK (timeout " + NETWORK_TIMEOUT_MS + " ms)");
            logI("============================================================");
            return true;
        } catch (Exception e) {
            logE("ConnectivityManager.requestNetwork FAILED", e);
            sendEvent("error", "WIFI REQUEST FAILED: " + safeMessage(e));
            return false;
        }
    }

    private void probeAndStartCamera(Network network) {
        logI("CAMERA PIPELINE: network available; probing Sony API");
        probeCameraSocket(network);
        try {
            JSONObject probe = callApi(network, "getApplicationInfo", new JSONArray());
            logI("CAMERA PROBE OK: " + compactJson(probe, 900));
            sendEvent("cameraProbe", compactJson(probe, 900));
        } catch (Exception e) {
            logW("CAMERA PROBE WARNING: " + safeMessage(e));
            sendEvent("engineWarning", "Sony API probe: " + safeMessage(e));
        }

        startCameraEngine(network);
    }

    private void probeCameraSocket(Network network) {
        if (network == null) {
            logW("CAMERA SOCKET PROBE skipped: network=null");
            return;
        }

        logI("CAMERA SOCKET PROBE -> " + CAMERA_IP + ":" + CAMERA_PORT);
        try (Socket socket = network.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(CAMERA_IP, CAMERA_PORT), 3000);
            logI("CAMERA SOCKET PROBE SUCCESS: TCP " + CAMERA_IP + ":" + CAMERA_PORT + " reachable");
        } catch (Exception e) {
            logE("CAMERA SOCKET PROBE FAILED: " + safeMessage(e));
            sendEvent("engineWarning", "TCP " + CAMERA_IP + ":" + CAMERA_PORT + " tidak dapat dijangkau: " + safeMessage(e));
        }
    }

    private void startCameraEngine(Network network) {
        executor.execute(() -> {
            isStreaming.set(false);
            firstFrameSent = false;
            lastFrameEventAt = 0L;

            logI("CAMERA ENGINE START");
            sendEvent("cameraEngine", "STARTING");

            try {
                logI("API -> startRecMode");
                JSONObject rec = callApi(network, "startRecMode", new JSONArray());
                logI("API <- startRecMode " + compactJson(rec, 1200));
                Thread.sleep(1500L);

                try {
                    logI("API -> setFocusMode [Continuous AF]");
                    JSONObject af = callApi(
                            network,
                            "setFocusMode",
                            new JSONArray().put("Continuous AF")
                    );
                    logI("API <- setFocusMode " + compactJson(af, 1000));
                } catch (Exception e) {
                    logW("setFocusMode warning: " + safeMessage(e));
                }
                Thread.sleep(500L);

                try {
                    logI("API -> setLiveviewSize [L]");
                    JSONObject size = callApi(
                            network,
                            "setLiveviewSize",
                            new JSONArray().put("L")
                    );
                    logI("API <- setLiveviewSize " + compactJson(size, 1000));
                } catch (Exception e) {
                    logW("setLiveviewSize warning: " + safeMessage(e));
                }
                Thread.sleep(500L);

                logI("API -> startLiveview");
                JSONObject liveview = callApi(
                        network,
                        "startLiveview",
                        new JSONArray()
                );
                logI("API <- startLiveview " + compactJson(liveview, 1500));

                JSONArray result = liveview.optJSONArray("result");
                if (result == null || result.length() == 0) {
                    throw new IllegalStateException(
                            "startLiveview returned no result[0]. Response="
                                    + compactJson(liveview, 1500)
                    );
                }

                String url = result.optString(0, "").trim();
                if (url.isEmpty()) {
                    throw new IllegalStateException("startLiveview returned empty stream URL");
                }

                lastLiveviewUrl = url;
                logI("LIVEVIEW URL='" + url + "'");
                sendEvent("liveviewActive", url);
                startStreaming(network, url);
            } catch (Exception e) {
                isStreaming.set(false);
                logE("CAMERA ENGINE FAILED", e);
                updateSystemStatus("CAMERA ENGINE ERROR", false);
                sendEvent("cameraError", "CAMERA ENGINE FAILED: " + safeMessage(e));
            }
        });
    }

    private JSONObject callApi(Network network, String method, JSONArray params) throws Exception {
        JSONObject request = new JSONObject();
        request.put("method", method);
        request.put("params", params == null ? new JSONArray() : params);
        request.put("id", 1);
        request.put("version", "1.0");

        String requestBody = request.toString();
        logI("HTTP POST " + API_URL + " method=" + method + " body=" + sanitize(requestBody, 700));

        URL url = new URL(API_URL);
        HttpURLConnection http;

        if (network != null) {
            http = (HttpURLConnection) network.openConnection(url);
        } else {
            http = (HttpURLConnection) url.openConnection();
        }

        try {
            http.setRequestMethod("POST");
            http.setDoOutput(true);
            http.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
            http.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            http.setRequestProperty("Accept", "application/json");
            http.setUseCaches(false);

            byte[] body = requestBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = http.getOutputStream()) {
                output.write(body);
                output.flush();
            }

            int code = http.getResponseCode();
            String response = readHttpBody(http, code);

            logI("HTTP <- method=" + method + " status=" + code
                    + " response=" + sanitize(response, 1400));

            if (code < 200 || code >= 300) {
                throw new IllegalStateException(
                        "HTTP " + code + " for " + method + ": " + sanitize(response, 500)
                );
            }

            JSONObject json = new JSONObject(response);
            if (json.has("error")) {
                JSONArray error = json.optJSONArray("error");
                throw new IllegalStateException(
                        "Sony API error for " + method + ": "
                                + (error == null ? compactJson(json, 700) : error.toString())
                );
            }

            return json;
        } finally {
            http.disconnect();
        }
    }

    private String readHttpBody(HttpURLConnection http, int statusCode) throws Exception {
        InputStream source = statusCode >= 400
                ? http.getErrorStream()
                : http.getInputStream();

        if (source == null) return "";

        try (Scanner scanner = new Scanner(
                new BufferedInputStream(source), StandardCharsets.UTF_8.name())) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private void startStreaming(Network network, String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            logE("Live View stream URL is empty");
            sendEvent("streamLost", "EMPTY LIVE VIEW URL");
            return;
        }

        isStreaming.set(true);
        firstFrameSent = false;
        lastFrameEventAt = 0L;
        updateSystemStatus("LIVE VIEW ACTIVE", true);
        logI("LIVEVIEW STREAM CONNECT START");
        logI("GET " + urlString);

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL streamUrl = new URL(urlString);
                if (network != null) {
                    connection = (HttpURLConnection) network.openConnection(streamUrl);
                } else {
                    connection = (HttpURLConnection) streamUrl.openConnection();
                }

                connection.setRequestMethod("GET");
                connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(0);
                connection.setUseCaches(false);

                int status = connection.getResponseCode();
                logI("LIVEVIEW HTTP status=" + status
                        + " contentType=" + connection.getContentType());

                if (status < 200 || status >= 300) {
                    String body = readHttpBody(connection, status);
                    throw new IllegalStateException(
                            "Live View HTTP " + status + ": " + sanitize(body, 700)
                    );
                }

                try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                    ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
                    byte[] buffer = new byte[16 * 1024];
                    final byte[] SOI = new byte[]{(byte) 0xFF, (byte) 0xD8};
                    final byte[] EOI = new byte[]{(byte) 0xFF, (byte) 0xD9};

                    while (isStreaming.get()) {
                        int count = input.read(buffer);
                        if (count < 0) {
                            logW("LIVEVIEW input.read() returned EOF");
                            break;
                        }
                        if (count == 0) continue;

                        accumulator.write(buffer, 0, count);
                        byte[] data = accumulator.toByteArray();

                        while (true) {
                            int start = findSeq(data, SOI, 0);
                            if (start < 0) {
                                if (data.length > 64 * 1024) {
                                    accumulator.reset();
                                    accumulator.write(data, data.length - 4096, 4096);
                                }
                                break;
                            }

                            int end = findSeq(data, EOI, start + 2);
                            if (end < 0) {
                                break;
                            }

                            int jpegEnd = end + 2;
                            int jpegLength = jpegEnd - start;
                            if (jpegLength <= 0 || jpegLength > 12 * 1024 * 1024) {
                                logW("Invalid JPEG length=" + jpegLength + "; resynchronizing");
                                accumulator.reset();
                                accumulator.write(data, Math.min(data.length, start + 2),
                                        Math.max(0, data.length - Math.min(data.length, start + 2)));
                                break;
                            }

                            byte[] jpeg = new byte[jpegLength];
                            System.arraycopy(data, start, jpeg, 0, jpegLength);
                            lastFrameBytes = jpeg;

                            long now = System.currentTimeMillis();
                            if (!firstFrameSent || now - lastFrameEventAt >= FRAME_EVENT_INTERVAL_MS) {
                                HashMap<String, Object> event = new HashMap<>();
                                event.put("bytes", jpeg);
                                event.put("quad", isQuadMode);
                                event.put("grayscale", grayscaleMode);
                                sendEvent("liveviewFrame", event);
                                lastFrameEventAt = now;
                            }

                            if (!firstFrameSent) {
                                firstFrameSent = true;
                                logI("LIVEVIEW FIRST JPEG RECEIVED bytes=" + jpeg.length);
                                sendEvent("firstLiveviewFrame", true);
                            }

                            byte[] remaining = new byte[data.length - jpegEnd];
                            System.arraycopy(data, jpegEnd, remaining, 0, remaining.length);
                            accumulator.reset();
                            accumulator.write(remaining);
                            data = remaining;
                        }

                        if (accumulator.size() > 5 * 1024 * 1024) {
                            byte[] safeTail = accumulator.toByteArray();
                            int lastStart = findSeq(
                                    safeTail,
                                    SOI,
                                    Math.max(0, safeTail.length - 131072)
                            );
                            accumulator.reset();
                            if (lastStart >= 0) {
                                accumulator.write(
                                        safeTail,
                                        lastStart,
                                        safeTail.length - lastStart
                                );
                            } else {
                                accumulator.write(
                                        safeTail,
                                        Math.max(0, safeTail.length - 4096),
                                        Math.min(4096, safeTail.length)
                                );
                            }
                        }
                    }
                }

                if (isStreaming.get()) {
                    isStreaming.set(false);
                    logW("LIVEVIEW STREAM ENDED WITHOUT explicit stop");
                    updateSystemStatus("LIVE VIEW DISCONNECTED", false);
                    sendEvent("streamLost", "LIVE VIEW STREAM ENDED");
                }
            } catch (Exception e) {
                if (isStreaming.get()) {
                    isStreaming.set(false);
                    logE("LIVEVIEW STREAM FAILED", e);
                    updateSystemStatus("LIVE VIEW DISCONNECTED", false);
                    sendEvent("streamLost", "LIVEVIEW FAILED: " + safeMessage(e));
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                logI("LIVEVIEW STREAM CONNECT STOP");
            }
        });
    }

    private void refreshLiveview() {
        logI("Live View refresh requested from Flutter");
        stopStreamingOnly();

        Network network = currentNetwork;
        if (network == null) {
            logW("refreshLiveview: currentNetwork=null");
            sendEvent("networkUnavailable", "NO CAMERA NETWORK");
            return;
        }

        executor.execute(() -> {
            try {
                Thread.sleep(350L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            startCameraEngine(network);
        });
    }

    private void takePicture() {
        executor.execute(() -> {
            byte[] frame = lastFrameBytes;
            if (frame == null || frame.length == 0) {
                logW("CAPTURE requested but no Live View JPEG is available");
                sendEvent("captureError", "LIVE VIEW FRAME NOT AVAILABLE");
                return;
            }

            logI("CAPTURE START using latest JPEG bytes=" + frame.length);
            try {
                String fileName = saveToGallery(frame);
                logI("CAPTURE PREVIEW SAVED -> " + fileName);
                sendEvent("captureSaved", fileName);

                Network network = currentNetwork;
                if (network == null) {
                    throw new IllegalStateException("Camera network disappeared before actTakePicture");
                }

                JSONObject shutter = callApi(
                        network,
                        "actTakePicture",
                        new JSONArray()
                );
                logI("API <- actTakePicture " + compactJson(shutter, 1200));
                sendEvent("shutterAck", fileName);
                logI("CAPTURE COMPLETE");
            } catch (Exception e) {
                logE("CAPTURE FAILED", e);
                sendEvent("captureError", "CAPTURE FAILED: " + safeMessage(e));
            }
        });
    }

    private String saveToGallery(byte[] jpeg) throws Exception {
        String timestamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.US
        ).format(new Date());
        String displayName = "LandCam_" + timestamp + ".jpg";
        ContentResolver resolver = getContentResolver();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/LandCam_Monitor"
            );
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            android.net.Uri uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
            );
            if (uri == null) {
                throw new IllegalStateException("MediaStore insert returned null");
            }

            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) {
                    throw new IllegalStateException("MediaStore output stream unavailable");
                }
                output.write(jpeg);
                output.flush();
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        } else {
            File pictures = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
            );
            File directory = new File(pictures, "LandCam_Monitor");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("Cannot create gallery directory");
            }

            File file = new File(directory, displayName);
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(jpeg);
                output.flush();
            }
        }

        return displayName;
    }

    private void triggerAutoFocus() {
        executor.execute(() -> {
            Network network = currentNetwork;
            if (network == null) {
                logW("AUTOFOCUS requested with no camera network");
                sendEvent("engineWarning", "NO CAMERA NETWORK FOR AUTOFOCUS");
                return;
            }

            try {
                logI("API -> actFocus");
                JSONObject focus = callApi(network, "actFocus", new JSONArray());
                logI("API <- actFocus " + compactJson(focus, 900));
                Thread.sleep(500L);
                logI("API -> cancelFocus");
                JSONObject cancel = callApi(network, "cancelFocus", new JSONArray());
                logI("API <- cancelFocus " + compactJson(cancel, 900));
                sendEvent("autofocusDone", true);
            } catch (Exception e) {
                logE("AUTOFOCUS FAILED", e);
                sendEvent("engineWarning", "AUTOFOCUS FAILED: " + safeMessage(e));
            }
        });
    }

    private void disconnectCamera() {
        logI("DISCONNECT requested");
        stopStreamingOnly();

        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
                logI("NetworkCallback unregistered");
            } catch (Exception e) {
                logW("NetworkCallback unregister warning: " + safeMessage(e));
            }
            networkCallback = null;
        }

        if (connectivityManager != null) {
            try {
                boolean unbound = connectivityManager.bindProcessToNetwork(null);
                logI("bindProcessToNetwork(null) -> " + unbound);
            } catch (Exception e) {
                logW("Unbind network warning: " + safeMessage(e));
            }
        }

        currentNetwork = null;
        lastLiveviewUrl = null;
        sendEvent("disconnected", true);
        updateSystemStatus("CAMERA DISCONNECTED", false);
    }

    private void stopStreamingOnly() {
        if (isStreaming.getAndSet(false)) {
            logI("Stopping existing Live View stream");
        }
    }

    private void logNetworkState(Network network) {
        try {
            if (connectivityManager != null) {
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
                if (caps != null) {
                    logI("NETWORK CAPABILITIES: " + capabilitiesToString(caps));
                } else {
                    logW("NETWORK CAPABILITIES: null");
                }

                LinkProperties props = connectivityManager.getLinkProperties(network);
                logI("NETWORK LINK PROPERTIES: " + props);
            }
        } catch (Exception e) {
            logW("Network state inspection failed: " + safeMessage(e));
        }
    }

    private String capabilitiesToString(NetworkCapabilities caps) {
        if (caps == null) return "null";
        return caps.toString();
    }

    private void updateSystemStatus(String status, boolean active) {
        HashMap<String, Object> data = new HashMap<>();
        data.put("status", status);
        data.put("active", active);
        sendEvent("systemStatus", data);
    }

    private void sendEvent(String type, Object data) {
        EventChannel.EventSink sink = eventSink;
        if (sink == null) {
            return;
        }

        HashMap<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("data", data);

        runOnUiThread(() -> {
            EventChannel.EventSink current = eventSink;
            if (current != null) {
                current.success(event);
            }
        });
    }

    private void logI(String message) {
        Log.i(TAG, message);
        sendLogEvent("INFO", message);
    }

    private void logW(String message) {
        Log.w(TAG, message);
        sendLogEvent("WARN", message);
    }

    private void logE(String message) {
        Log.e(TAG, message);
        sendLogEvent("ERROR", message);
    }

    private void logE(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
        sendLogEvent("ERROR", message + ": " + safeMessage(throwable));
    }

    private void sendLogEvent(String level, String message) {
        HashMap<String, Object> data = new HashMap<>();
        data.put("level", level);
        data.put("message", message);
        data.put("time", new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()));

        synchronized (pendingLogEvents) {
            if (eventSink == null) {
                if (pendingLogEvents.size() >= 250) {
                    pendingLogEvents.remove(0);
                }
                pendingLogEvents.add(data);
                return;
            }
        }

        sendEvent("log", data);
    }

    private void flushPendingLogs() {
        ArrayList<HashMap<String, Object>> copy;
        synchronized (pendingLogEvents) {
            if (pendingLogEvents.isEmpty()) return;
            copy = new ArrayList<>(pendingLogEvents);
            pendingLogEvents.clear();
        }

        for (HashMap<String, Object> data : copy) {
            sendEvent("log", data);
        }
    }

    private HashMap<String, Object> mapOf(String key, Object value) {
        HashMap<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) return "unknown";
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null) return "null";
        String compact = value.replace('\n', ' ').replace('\r', ' ');
        if (compact.length() <= maxLength) return compact;
        return compact.substring(0, maxLength) + "…";
    }

    private static String compactJson(JSONObject object, int maxLength) {
        return sanitize(object == null ? "null" : object.toString(), maxLength);
    }

    private static String maskSecret(String value) {
        if (value == null || value.isEmpty()) return "<empty>";
        if (value.length() <= 2) return "**";
        return value.substring(0, 2) + "******";
    }

    private static String bytesToHex(byte[] data, int maxBytes) {
        if (data == null) return "null";
        int count = Math.min(data.length, Math.max(0, maxBytes));
        StringBuilder out = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            out.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        if (data.length > count) out.append("…");
        return out.toString();
    }

    private static int findSeq(byte[] data, byte[] seq, int offset) {
        if (data == null || seq == null || seq.length == 0) return -1;
        int start = Math.max(0, offset);
        outer:
        for (int i = start; i <= data.length - seq.length; i++) {
            for (int j = 0; j < seq.length; j++) {
                if (data[i + j] != seq[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @Override
    protected void onDestroy() {
        logI("LANDCAM native transport destroying");
        stopStreamingOnly();

        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }

        if (connectivityManager != null) {
            try {
                connectivityManager.bindProcessToNetwork(null);
            } catch (Exception ignored) {
            }
        }

        currentNetwork = null;
        executor.shutdownNow();
        super.onDestroy();
    }
}
