package com.example.landcam;

import android.content.Context;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LANDCAM camera discovery.
 *
 * Discovery order:
 *
 *   1. SSDP
 *   2. Direct device-description probing
 *   3. Full subnet / host scan
 *
 * The class only discovers and validates a camera endpoint.
 * Camera session control remains in CameraEngine.
 */
public final class CameraDiscovery {

    private static final String TAG = "LandCamDiscovery";

    private static final String SONY_SCALAR_ST =
            "urn:schemas-sony-com:service:ScalarWebAPI:1";

    private static final String SSDP_ADDRESS =
            "239.255.255.250";

    private static final int SSDP_PORT =
            1900;

    private static final int HTTP_PROBE_TIMEOUT_MS =
            2200;

    private static final int TCP_PROBE_TIMEOUT_MS =
            700;

    private static final int API_PROBE_TIMEOUT_MS =
            2600;

    private static final int DISCOVERY_THREADS =
            24;

    private static final int DISCOVERY_MAX_HOSTS =
            254;

    private static final long SSDP_TIMEOUT_MS =
            3500L;

    private static final long DISCOVERY_TOTAL_TIMEOUT_MS =
            15000L;

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

    private final Context context;

    private final ExecutorService discoveryExecutor;

    private final Listener listener;

    private final WifiManager wifiManager;

    private volatile WifiManager.MulticastLock multicastLock;

    private volatile boolean destroyed;

    public interface Listener {
        void onEndpointDiscovered(
                CameraEngine.CameraEndpoint endpoint
        );
    }

    public CameraDiscovery(
            Context context,
            ExecutorService executor,
            Listener listener
    ) {
        this.context =
                context.getApplicationContext();

        this.discoveryExecutor =
                executor != null
                        ? executor
                        : java.util.concurrent.Executors
                        .newFixedThreadPool(
                                DISCOVERY_THREADS
                        );

        this.listener =
                listener;

        this.wifiManager =
                (WifiManager)
                        this.context.getSystemService(
                                Context.WIFI_SERVICE
                        );
    }

    /**
     * Main discovery entry point.
     */
    public CameraEngine.CameraEndpoint discover(
            Network network
    ) {
        if (
                destroyed
                        || network == null
        ) {
            return null;
        }

        log(
                "INFO",
                "Camera discovery started"
        );

        CameraEngine.CameraEndpoint endpoint;

        endpoint =
                discoverViaSsdp(
                        network
                );

        if (
                endpoint != null
        ) {
            notifyEndpoint(
                    endpoint
            );
            return endpoint;
        }

        if (isDestroyed()) {
            return null;
        }

        endpoint =
                discoverViaDescription(
                        network
                );

        if (
                endpoint != null
        ) {
            notifyEndpoint(
                    endpoint
            );
            return endpoint;
        }

        if (isDestroyed()) {
            return null;
        }

        endpoint =
                discoverViaNetworkScan(
                        network
                );

        if (
                endpoint != null
        ) {
            notifyEndpoint(
                    endpoint
            );
            return endpoint;
        }

        log(
                "WARN",
                "No supported camera endpoint found"
        );

        return null;
    }

    public void destroy() {
        destroyed = true;
        releaseMulticastLock();
    }

    private void notifyEndpoint(
            CameraEngine.CameraEndpoint endpoint
    ) {
        if (
                listener == null
                        || endpoint == null
        ) {
            return;
        }

        try {
            listener.onEndpointDiscovered(
                    endpoint
            );
        } catch (Exception e) {
            Log.e(
                    TAG,
                    "Endpoint callback failed",
                    e
            );
        }
    }

    // ---------------------------------------------------------------------
    // SSDP
    // ---------------------------------------------------------------------

    private CameraEngine.CameraEndpoint discoverViaSsdp(
            Network network
    ) {
        if (isDestroyed()) {
            return null;
        }

        log(
                "INFO",
                "Trying SSDP discovery"
        );

        acquireMulticastLock();

        try {
            String[] targets =
                    new String[]{
                            SONY_SCALAR_ST,
                            "ssdp:all"
                    };

            for (
                    String searchTarget
                    : targets
            ) {
                if (isDestroyed()) {
                    return null;
                }

                try {
                    List<String> locations =
                            ssdpSearch(
                                    network,
                                    searchTarget
                            );

                    for (
                            String location
                            : locations
                    ) {
                        if (isDestroyed()) {
                            return null;
                        }

                        log(
                                "INFO",
                                "SSDP LOCATION: "
                                        + location
                        );

                        CameraEngine.CameraEndpoint endpoint =
                                endpointFromDescriptionLocation(
                                        network,
                                        location
                                );

                        if (
                                endpoint != null
                        ) {

                            log(
                                    "INFO",
                                    "Camera discovered via SSDP"
                            );

                            return endpoint;
                        }
                    }

                } catch (Exception e) {

                    log(
                            "WARN",
                            "SSDP search failed for "
                                    + searchTarget
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
            } catch (Exception e) {
                log(
                        "WARN",
                        "Could not bind SSDP socket to camera network: "
                                + safeMessage(e)
                );
            }

            socket.setSoTimeout(
                    350
            );

            DatagramPacket request =
                    new DatagramPacket(
                            requestBytes,
                            requestBytes.length,
                            multicast,
                            SSDP_PORT
                    );

            socket.send(
                    request
            );

            sleepQuietly(
                    60L
            );

            socket.send(
                    request
            );

            byte[] buffer =
                    new byte[16384];

            while (
                    !isDestroyed()
                            && System.currentTimeMillis()
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

                    Matcher matcher =
                            SSDP_LOCATION_PATTERN.matcher(
                                    response
                            );

                    while (
                            matcher.find()
                    ) {

                        String location =
                                matcher.group(
                                        1
                                );

                        if (
                                location != null
                                        && !location
                                        .trim()
                                        .isEmpty()
                        ) {

                            locations.add(
                                    location.trim()
                            );
                        }
                    }

                } catch (
                        SocketTimeoutException timeout
                ) {
                    // Continue until discovery window expires.
                }
            }
        }

        return new ArrayList<>(
                locations
        );
    }

    // ---------------------------------------------------------------------
    // Device description
    // ---------------------------------------------------------------------

    private CameraEngine.CameraEndpoint endpointFromDescriptionLocation(
            Network network,
            String location
    ) {
        if (
                location == null
                        || location.trim().isEmpty()
        ) {
            return null;
        }

        try {

            URL descriptionUrl =
                    new URL(
                            location.trim()
                    );

            String xml =
                    readText(
                            network,
                            descriptionUrl,
                            HTTP_PROBE_TIMEOUT_MS
                    );

            if (
                    xml == null
                            || xml.trim().isEmpty()
            ) {
                return null;
            }

            return endpointFromDescriptionXml(
                    network,
                    descriptionUrl,
                    xml
            );

        } catch (Exception e) {

            log(
                    "WARN",
                    "Description fetch failed: "
                            + safeMessage(e)
            );

            return null;
        }
    }

    private CameraEngine.CameraEndpoint endpointFromDescriptionXml(
            Network network,
            URL sourceUrl,
            String xml
    ) {
        if (
                xml == null
                        || xml.trim().isEmpty()
        ) {
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

        LinkedHashSet<String> actionUrls =
                new LinkedHashSet<>();

        String baseUrl =
                firstXmlValue(
                        XML_BASE_URL_PATTERN,
                        xml
                );

        if (
                baseUrl != null
                        && !baseUrl.isEmpty()
        ) {
            actionUrls.add(
                    baseUrl
            );
        }

        Matcher matcher =
                XML_ACTION_URL_PATTERN.matcher(
                        xml
                );

        while (
                matcher.find()
        ) {

            String actionUrl =
                    cleanXmlValue(
                            matcher.group(
                                    1
                            )
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

            List<String> candidates =
                    buildCameraApiCandidates(
                            actionUrl
                    );

            for (
                    String candidate
                    : candidates
            ) {

                String apiUrl =
                        normalizeApiUrl(
                                candidate
                        );

                if (
                        apiUrl == null
                                || apiUrl.isEmpty()
                ) {
                    continue;
                }

                try {

                    URL parsed =
                            new URL(
                                    apiUrl
                            );

                    String host =
                            parsed.getHost();

                    if (
                            host == null
                                    || host.isEmpty()
                    ) {
                        host =
                                sourceUrl.getHost();
                    }

                    String scheme =
                            schemeFromUrl(
                                    apiUrl
                            );

                    int port =
                            effectivePort(
                                    apiUrl,
                                    sourceUrl.getPort()
                            );

                    CameraEngine.CameraEndpoint endpoint =
                            new CameraEngine.CameraEndpoint(
                                    host,
                                    port,
                                    scheme,
                                    apiUrl,
                                    friendlyName,
                                    modelName
                            );

                    if (
                            probeCameraApi(
                                    network,
                                    endpoint
                            )
                    ) {
                        return endpoint;
                    }

                } catch (Exception ignored) {
                }
            }
        }

        return null;
    }

    private CameraEngine.CameraEndpoint discoverViaDescription(
            Network network
    ) {
        if (isDestroyed()) {
            return null;
        }

        log(
                "INFO",
                "Trying direct device-description discovery"
        );

        LinkedHashSet<String> hosts =
                new LinkedHashSet<>(
                        collectCandidateHosts(
                                network
                        )
                );

        for (
                String host
                : hosts
        ) {

            if (isDestroyed()) {
                return null;
            }

            if (
                    host == null
                            || host.trim().isEmpty()
            ) {
                continue;
            }

            for (
                    int port
                    : SONY_DESCRIPTION_PORTS
            ) {

                if (isDestroyed()) {
                    return null;
                }

                for (
                        String path
                        : DESCRIPTION_PATHS
                ) {

                    if (isDestroyed()) {
                        return null;
                    }

                    try {

                        URL descriptionUrl =
                                new URL(
                                        "http",
                                        host,
                                        port,
                                        path
                                );

                        String xml =
                                readText(
                                        network,
                                        descriptionUrl,
                                        HTTP_PROBE_TIMEOUT_MS
                                );

                        if (
                                xml == null
                                        || xml.trim().isEmpty()
                        ) {
                            continue;
                        }

                        CameraEngine.CameraEndpoint endpoint =
                                endpointFromDescriptionXml(
                                        network,
                                        descriptionUrl,
                                        xml
                                );

                        if (
                                endpoint != null
                        ) {

                            log(
                                    "INFO",
                                    "Camera discovered via direct description"
                            );

                            return endpoint;
                        }

                    } catch (Exception ignored) {
                    }
                }
            }
        }

        return null;
    }

    // ---------------------------------------------------------------------
    // Full network scan
    // ---------------------------------------------------------------------

    private CameraEngine.CameraEndpoint discoverViaNetworkScan(
            Network network
    ) {
        if (isDestroyed()) {
            return null;
        }

        List<String> hosts =
                collectFullScanHosts(
                        network
                );

        if (
                hosts.isEmpty()
        ) {
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

        CompletionService<CameraEngine.CameraEndpoint>
                completionService =
                new ExecutorCompletionService<>(
                        discoveryExecutor
                );

        List<Future<CameraEngine.CameraEndpoint>>
                futures =
                new ArrayList<>();

        for (
                String host
                : hosts
        ) {

            if (isDestroyed()) {
                break;
            }

            futures.add(
                    completionService.submit(
                            () ->
                                    probeHostForCamera(
                                            network,
                                            host
                                    )
                    )
            );
        }

        int completed =
                0;

        try {

            while (
                    !isDestroyed()
                            && completed < futures.size()
                            && System.currentTimeMillis()
                            < deadline
            ) {

                long remaining =
                        deadline
                                - System.currentTimeMillis();

                Future<CameraEngine.CameraEndpoint> future =
                        completionService.poll(
                                Math.max(
                                        1L,
                                        remaining
                                ),
                                TimeUnit.MILLISECONDS
                        );

                if (
                        future == null
                ) {
                    break;
                }

                completed++;

                try {

                    CameraEngine.CameraEndpoint endpoint =
                            future.get();

                    if (
                            endpoint != null
                    ) {

                        for (
                                Future<CameraEngine.CameraEndpoint>
                                pending
                                : futures
                        ) {

                            if (
                                    !pending.isDone()
                            ) {
                                pending.cancel(
                                        true
                                );
                            }
                        }

                        return endpoint;
                    }

                } catch (Exception ignored) {
                }
            }

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

        } finally {

            for (
                    Future<CameraEngine.CameraEndpoint>
                    future
                    : futures
            ) {

                if (
                        !future.isDone()
                ) {
                    future.cancel(
                            true
                    );
                }
            }
        }

        return null;
    }

    private CameraEngine.CameraEndpoint probeHostForCamera(
            Network network,
            String host
    ) {
        if (
                isDestroyed()
                        || Thread.currentThread()
                        .isInterrupted()
        ) {
            return null;
        }

        for (
                int port
                : SONY_API_PORTS
        ) {

            if (
                    isDestroyed()
                            || Thread.currentThread()
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

            String scheme =
                    port == 443
                            ? "https"
                            : "http";

            for (
                    String apiPath
                    : new String[]{
                    "/sony/camera",
                    "/camera",
                    "/sony"
            }
            ) {

                if (
                        isDestroyed()
                                || Thread.currentThread()
                                .isInterrupted()
                ) {
                    return null;
                }

                CameraEngine.CameraEndpoint endpoint =
                        new CameraEngine.CameraEndpoint(
                                host,
                                port,
                                scheme,
                                scheme
                                        + "://"
                                        + host
                                        + ":"
                                        + port
                                        + apiPath,
                                "",
                                ""
                        );

                if (
                        probeCameraApi(
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
        if (
                network == null
                        || host == null
        ) {
            return false;
        }

        try (
                Socket socket =
                        new Socket()
        ) {

            try {
                network.bindSocket(
                        socket
                );
            } catch (Exception ignored) {
            }

            socket.connect(
                    new java.net.InetSocketAddress(
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

    /**
     * Lightweight JSON-RPC validation.
     *
     * getApplicationInfo is deliberately used instead of starting camera
     * recording here. CameraEngine owns the actual camera session.
     */
    private boolean probeCameraApi(
            Network network,
            CameraEngine.CameraEndpoint endpoint
    ) {
        if (
                network == null
                        || endpoint == null
                        || endpoint.apiUrl == null
        ) {
            return false;
        }

        HttpURLConnection connection =
                null;

        try {

            URL url =
                    new URL(
                            endpoint.apiUrl
                    );

            URLConnection raw =
                    network.openConnection(
                            url
                    );

            if (
                    !(raw instanceof HttpURLConnection)
            ) {
                return false;
            }

            connection =
                    (HttpURLConnection) raw;

            connection.setRequestMethod(
                    "POST"
            );

            connection.setConnectTimeout(
                    API_PROBE_TIMEOUT_MS
            );

            connection.setReadTimeout(
                    API_PROBE_TIMEOUT_MS
            );

            connection.setUseCaches(
                    false
            );

            connection.setDoInput(
                    true
            );

            connection.setDoOutput(
                    true
            );

            connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
            );

            connection.setRequestProperty(
                    "Accept",
                    "application/json"
            );

            JSONObject request =
                    new JSONObject();

            request.put(
                    "method",
                    "getApplicationInfo"
            );

            request.put(
                    "params",
                    new JSONArray()
            );

            request.put(
                    "id",
                    1
            );

            request.put(
                    "version",
                    "1.0"
            );

            byte[] body =
                    request
                            .toString()
                            .getBytes(
                                    StandardCharsets.UTF_8
                            );

            try (
                    OutputStream output =
                            connection
                                    .getOutputStream()
            ) {

                output.write(
                        body
                );

                output.flush();
            }

            int code =
                    connection.getResponseCode();

            if (
                    code < 200
                            || code >= 400
            ) {
                return false;
            }

            InputStream input =
                    connection.getInputStream();

            if (
                    input == null
            ) {
                return false;
            }

            String response =
                    readText(
                            input
                    );

            if (
                    response == null
                            || response.trim().isEmpty()
            ) {
                return false;
            }

            JSONObject json =
                    new JSONObject(
                            response
                    );

            /*
             * The strongest validation is a JSON-RPC response with either
             * result or error. Cameras can legitimately answer with an API
             * error object while still proving that the endpoint is ours.
             */
            return json.has("result")
                    || json.has("error");

        } catch (Exception e) {

            return false;

        } finally {

            if (
                    connection != null
            ) {
                connection.disconnect();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Candidate network addresses
    // ---------------------------------------------------------------------

    private List<String> collectCandidateHosts(
            Network network
    ) {
        LinkedHashSet<String> candidates =
                new LinkedHashSet<>();

        LinkProperties properties =
                getLinkProperties(
                        network
                );

        if (
                properties == null
        ) {
            addFallbackHosts(
                    candidates
            );

            return new ArrayList<>(
                    candidates
            );
        }

        for (
                RouteInfo route
                : properties.getRoutes()
        ) {

            InetAddress gateway =
                    route.getGateway();

            if (
                    gateway instanceof Inet4Address
                            && !gateway
                            .isLoopbackAddress()
                            && !gateway
                            .isAnyLocalAddress()
            ) {

                candidates.add(
                        gateway.getHostAddress()
                );
            }
        }

        for (
                LinkAddress linkAddress
                : properties.getLinkAddresses()
        ) {

            if (
                    !(linkAddress
                            .getAddress()
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
                    8
            );
        }

        addFallbackHosts(
                candidates
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

        LinkProperties properties =
                getLinkProperties(
                        network
                );

        if (
                properties == null
        ) {
            return new ArrayList<>();
        }

        for (
                RouteInfo route
                : properties.getRoutes()
        ) {

            InetAddress gateway =
                    route.getGateway();

            if (
                    gateway instanceof Inet4Address
            ) {

                String host =
                        gateway.getHostAddress();

                if (
                        isIpv4(
                                host
                        )
                ) {
                    hosts.add(
                            host
                    );
                }
            }
        }

        for (
                LinkAddress address
                : properties.getLinkAddresses()
        ) {

            if (
                    !(address.getAddress()
                            instanceof Inet4Address)
            ) {
                continue;
            }

            Inet4Address local =
                    (Inet4Address)
                            address.getAddress();

            if (
                    local.isLoopbackAddress()
                            || local.isAnyLocalAddress()
            ) {
                continue;
            }

            addSubnetHosts(
                    hosts,
                    local,
                    address.getPrefixLength()
            );

            if (
                    hosts.size()
                            >= DISCOVERY_MAX_HOSTS
            ) {
                break;
            }
        }

        if (
                hosts.isEmpty()
        ) {

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

        ArrayList<String> result =
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

    private void addFallbackHosts(
            LinkedHashSet<String> target
    ) {
        Collections.addAll(
                target,
                "192.168.122.1",
                "192.168.1.1",
                "192.168.0.1",
                "192.168.2.1",
                "10.0.0.1",
                "10.0.0.2",
                "169.254.1.1"
        );
    }

    private void addNearbyHosts(
            LinkedHashSet<String> target,
            Inet4Address local,
            int prefixLength,
            int radius
    ) {
        if (
                local == null
        ) {
            return;
        }

        byte[] bytes =
                local.getAddress();

        int value =
                ipv4ToInt(
                        bytes
                );

        int safePrefix =
                Math.max(
                        0,
                        Math.min(
                                30,
                                prefixLength
                        )
                );

        int mask =
                safePrefix == 0
                        ? 0
                        : -1
                        << (
                        32 - safePrefix
                );

        int networkPart =
                value & mask;

        int hostValue =
                value & ~mask;

        for (
                int delta = -radius;
                delta <= radius;
                delta++
        ) {

            int candidate =
                    hostValue
                            + delta;

            if (
                    candidate < 1
                            || candidate > 254
            ) {
                continue;
            }

            int ip =
                    networkPart
                            | candidate;

            target.add(
                    intToIpv4(
                            ip
                    )
            );
        }

        target.add(
                intToIpv4(
                        networkPart
                                | 1
                )
        );
    }

    private void addSubnetHosts(
            LinkedHashSet<String> target,
            Inet4Address local,
            int prefixLength
    ) {
        if (
                local == null
        ) {
            return;
        }

        int safePrefix =
                Math.max(
                        0,
                        Math.min(
                                30,
                                prefixLength
                        )
                );

        /*
         * A very small prefix can represent thousands of hosts.
         * LANDCAM intentionally caps scans to a manageable set.
         */
        if (
                safePrefix < 24
        ) {

            addNearbyHosts(
                    target,
                    local,
                    safePrefix,
                    126
            );

            return;
        }

        int value =
                ipv4ToInt(
                        local.getAddress()
                );

        int mask =
                safePrefix == 0
                        ? 0
                        : -1
                        << (
                        32 - safePrefix
                );

        int networkPart =
                value & mask;

        int hostCount =
                1 << (
                        32 - safePrefix
                );

        int usableCount =
                Math.min(
                        hostCount - 2,
                        DISCOVERY_MAX_HOSTS
                );

        for (
                int host = 1;
                host <= usableCount;
                host++
        ) {

            target.add(
                    intToIpv4(
                            networkPart
                                    | host
                    )
            );

            if (
                    target.size()
                            >= DISCOVERY_MAX_HOSTS
            ) {
                return;
            }
        }
    }

    // ---------------------------------------------------------------------
    // XML / URL helpers
    // ---------------------------------------------------------------------

    private String chooseActionUrl(
            String xml
    ) {
        if (
                xml == null
        ) {
            return null;
        }

        Matcher matcher =
                XML_ACTION_URL_PATTERN.matcher(
                        xml
                );

        while (
                matcher.find()
        ) {

            String value =
                    cleanXmlValue(
                            matcher.group(
                                    1
                            )
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

        while (
                base.endsWith(
                        "/"
                )
        ) {

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
        if (
                apiUrl == null
                        || apiUrl.trim().isEmpty()
        ) {
            return null;
        }

        String value =
                apiUrl.trim();

        while (
                value.endsWith(
                        "/"
                )
        ) {

            value =
                    value.substring(
                            0,
                            value.length() - 1
                    );
        }

        return value;
    }

    private String schemeFromUrl(
            String url
    ) {
        if (
                url == null
        ) {
            return "http";
        }

        try {

            String scheme =
                    new URL(url)
                            .getProtocol();

            return scheme == null
                    || scheme.isEmpty()
                    ? "http"
                    : scheme;

        } catch (Exception e) {

            return url
                    .toLowerCase(
                            Locale.US
                    )
                    .startsWith(
                            "https://"
                    )
                    ? "https"
                    : "http";
        }
    }

    private int effectivePort(
            String url,
            int fallback
    ) {
        if (
                url == null
        ) {
            return fallback > 0
                    ? fallback
                    : 80;
        }

        try {

            URL parsed =
                    new URL(
                            url
                    );

            if (
                    parsed.getPort() > 0
            ) {
                return parsed.getPort();
            }

            if (
                    parsed.getDefaultPort() > 0
            ) {
                return parsed.getDefaultPort();
            }

        } catch (Exception ignored) {
        }

        return fallback > 0
                ? fallback
                : 80;
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

        if (
                !matcher.find()
        ) {
            return null;
        }

        return cleanXmlValue(
                matcher.group(
                        1
                )
        );
    }

    private String cleanXmlValue(
            String value
    ) {
        if (
                value == null
        ) {
            return null;
        }

        String result =
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

        result =
                result
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

        return result;
    }

    // ---------------------------------------------------------------------
    // Network I/O
    // ---------------------------------------------------------------------

    private String readText(
            Network network,
            URL url,
            int timeoutMs
    ) throws Exception {

        URLConnection raw =
                network.openConnection(
                        url
                );

        if (
                !(raw instanceof HttpURLConnection)
        ) {
            throw new IllegalStateException(
                    "URL IS NOT HTTP"
            );
        }

        HttpURLConnection connection =
                (HttpURLConnection) raw;

        try {

            connection.setRequestMethod(
                    "GET"
            );

            connection.setConnectTimeout(
                    timeoutMs
            );

            connection.setReadTimeout(
                    timeoutMs
            );

            connection.setUseCaches(
                    false
            );

            connection.setInstanceFollowRedirects(
                    true
            );

            connection.setRequestProperty(
                    "Accept",
                    "application/xml,text/xml,text/plain,*/*"
            );

            int code =
                    connection.getResponseCode();

            if (
                    code < 200
                            || code >= 400
            ) {
                return null;
            }

            InputStream input =
                    connection.getInputStream();

            if (
                    input == null
            ) {
                return null;
            }

            return readText(
                    input
            );

        } finally {

            connection.disconnect();
        }
    }

    private String readText(
            InputStream input
    ) throws Exception {

        try (
                InputStream in =
                        new BufferedInputStream(
                                input
                        );

                ByteArrayOutputStream output =
                        new ByteArrayOutputStream()
        ) {

            byte[] buffer =
                    new byte[8192];

            int count;

            while (
                    (count =
                            in.read(
                                    buffer
                            )
                    ) >= 0
            ) {

                if (
                        count == 0
                ) {
                    continue;
                }

                output.write(
                        buffer,
                        0,
                        count
                );
            }

            return output.toString(
                    StandardCharsets.UTF_8.name()
            );
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private LinkProperties getLinkProperties(
            Network network
    ) {
        if (
                network == null
        ) {
            return null;
        }

        try {

            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager)
                            context.getSystemService(
                                    Context.CONNECTIVITY_SERVICE
                            );

            if (
                    cm == null
            ) {
                return null;
            }

            return cm.getLinkProperties(
                    network
            );

        } catch (Exception e) {

            return null;
        }
    }

    private void acquireMulticastLock() {
        if (
                wifiManager == null
        ) {
            return;
        }

        try {

            if (
                    multicastLock == null
            ) {

                multicastLock =
                        wifiManager
                                .createMulticastLock(
                                        TAG
                                );

                multicastLock
                        .setReferenceCounted(
                                true
                        );
            }

            if (
                    !multicastLock.isHeld()
            ) {
                multicastLock.acquire();
            }

        } catch (Exception e) {

            log(
                    "WARN",
                    "Multicast lock failed: "
                            + safeMessage(e)
            );
        }
    }

    private void releaseMulticastLock() {
        WifiManager.MulticastLock lock =
                multicastLock;

        multicastLock =
                null;

        if (
                lock == null
        ) {
            return;
        }

        try {

            if (
                    lock.isHeld()
            ) {
                lock.release();
            }

        } catch (Exception ignored) {
        }
    }

    private boolean isDestroyed() {
        return destroyed;
    }

    private static boolean isIpv4(
            String host
    ) {
        if (
                host == null
        ) {
            return false;
        }

        String[] parts =
                host.split(
                        "\\."
                );

        if (
                parts.length != 4
        ) {
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
        if (
                host == null
        ) {
            return null;
        }

        try {

            InetAddress address =
                    InetAddress.getByName(
                            host
                    );

            if (
                    address
                            instanceof Inet4Address
            ) {

                return (Inet4Address)
                        address;
            }

        } catch (Exception ignored) {
        }

        return null;
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

    private static void sleepQuietly(
            long millis
    ) {
        try {

            Thread.sleep(
                    millis
            );

        } catch (
                InterruptedException e
        ) {

            Thread.currentThread()
                    .interrupt();
        }
    }

    private static String safeMessage(
            Throwable error
    ) {
        if (
                error == null
        ) {
            return "UNKNOWN ERROR";
        }

        String message =
                error.getMessage();

        if (
                message == null
                        || message.trim().isEmpty()
        ) {
            return error
                    .getClass()
                    .getSimpleName();
        }

        return message;
    }

    private void log(
            String level,
            String message
    ) {
        String text =
                "["
                        + (
                        level == null
                                ? "INFO"
                                : level
                                .toUpperCase(
                                        Locale.US
                                )
                )
                        + "] "
                        + (
                        message == null
                                ? ""
                                : message
                );

        if (
                "ERROR".equalsIgnoreCase(
                        level
                )
        ) {

            Log.e(
                    TAG,
                    text
            );

        } else if (
                "WARN".equalsIgnoreCase(
                        level
                )
        ) {

            Log.w(
                    TAG,
                    text
            );

        } else {

            Log.i(
                    TAG,
                    text
            );
        }
    }
}