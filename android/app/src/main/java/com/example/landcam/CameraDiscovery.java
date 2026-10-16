package com.example.landcam;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
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
import java.net.InetSocketAddress;
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
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds and validates a camera endpoint on the selected camera network.
 *
 * <p>Discovery order is SSDP, device-description probing, then a bounded
 * host/API scan. This class does not own the camera session; CameraEngine
 * remains responsible for connecting to the discovered endpoint and starting
 * live view.</p>
 *
 * <p>Call {@link #discover(Network)} from a worker thread, never the Android UI
 * thread. A supplied executor is borrowed and is not shut down by this class.
 * An executor created by this class is owned and is shut down by destroy().</p>
 */
public final class CameraDiscovery {
    private static final String TAG = "LandCamDiscovery";

    private static final String SONY_SCALAR_ST =
            "urn:schemas-sony-com:service:ScalarWebAPI:1";
    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    private static final int HTTP_PROBE_TIMEOUT_MS = 850;
    private static final int TCP_PROBE_TIMEOUT_MS = 550;
    private static final int API_PROBE_TIMEOUT_MS = 1200;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int DISCOVERY_THREADS = 12;
    private static final int DISCOVERY_MAX_HOSTS = 254;
    private static final int MAX_DESCRIPTION_HOSTS = 32;

    private static final long SSDP_TIMEOUT_MS = 3000L;
    private static final long DESCRIPTION_BUDGET_MS = 4500L;
    private static final long DISCOVERY_TOTAL_TIMEOUT_MS = 15000L;

    private static final List<Integer> SONY_API_PORTS = Arrays.asList(
            8080, 10000, 80, 61000
    );
    private static final List<Integer> SONY_DESCRIPTION_PORTS = Arrays.asList(
            64321, 61000, 8080, 10000, 80
    );
    private static final List<String> DESCRIPTION_PATHS = Arrays.asList(
            "/sony/ssdp/dd.xml", "/scalarwebapi_dd.xml", "/dd.xml"
    );

    private static final Pattern SSDP_LOCATION_PATTERN = Pattern.compile(
            "(?im)^location\\s*:\\s*(\\S+)\\s*$"
    );
    private static final Pattern XML_ACTION_URL_PATTERN = Pattern.compile(
            "<[^>]*X_ScalarWebAPI_ActionList_URL[^>]*>(.*?)</[^>]*X_ScalarWebAPI_ActionList_URL>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern XML_BASE_URL_PATTERN = Pattern.compile(
            "<[^>]*X_ScalarWebAPI_BaseURL[^>]*>(.*?)</[^>]*X_ScalarWebAPI_BaseURL>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern XML_FRIENDLY_NAME_PATTERN = Pattern.compile(
            "<[^>]*friendlyName[^>]*>(.*?)</[^>]*friendlyName>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern XML_MODEL_NAME_PATTERN = Pattern.compile(
            "<[^>]*modelName[^>]*>(.*?)</[^>]*modelName>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private final Context context;
    private final ExecutorService discoveryExecutor;
    private final boolean ownsExecutor;
    private final Listener listener;
    private final WifiManager wifiManager;
    private final ConnectivityManager connectivityManager;
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final Object multicastLockGuard = new Object();
    private volatile WifiManager.MulticastLock multicastLock;

    public interface Listener {
        void onEndpointDiscovered(CameraEngine.CameraEndpoint endpoint);
    }

    public CameraDiscovery(Context context, ExecutorService executor, Listener listener) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        if (executor != null) {
            this.discoveryExecutor = executor;
            this.ownsExecutor = false;
        } else {
            this.discoveryExecutor = Executors.newFixedThreadPool(DISCOVERY_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "LandCamDiscovery");
                thread.setDaemon(true);
                return thread;
            });
            this.ownsExecutor = true;
        }
        this.listener = listener;
        this.wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        this.connectivityManager = (ConnectivityManager)
                this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /** Performs bounded discovery. Invoke from a background thread. */
    public CameraEngine.CameraEndpoint discover(Network network) {
        if (isDestroyed() || network == null) {
            return null;
        }

        final long deadline = System.currentTimeMillis() + DISCOVERY_TOTAL_TIMEOUT_MS;
        log("INFO", "Camera discovery started");

        CameraEngine.CameraEndpoint endpoint = discoverViaSsdp(network, deadline);
        if (endpoint != null && !isDestroyed()) {
            notifyEndpoint(endpoint);
            return endpoint;
        }
        if (isDestroyed() || remainingMillis(deadline) <= 0L) {
            return null;
        }

        endpoint = discoverViaDescription(
                network,
                Math.min(deadline, System.currentTimeMillis() + DESCRIPTION_BUDGET_MS)
        );
        if (endpoint != null && !isDestroyed()) {
            notifyEndpoint(endpoint);
            return endpoint;
        }
        if (isDestroyed() || remainingMillis(deadline) <= 0L) {
            log("WARN", "Discovery time budget expired before full scan");
            return null;
        }

        endpoint = discoverViaNetworkScan(network, deadline);
        if (endpoint != null && !isDestroyed()) {
            notifyEndpoint(endpoint);
            return endpoint;
        }

        log("WARN", "No supported camera endpoint found");
        return null;
    }

    /** Stops discovery and releases owned resources. Safe to call more than once. */
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        releaseMulticastLock();
        if (ownsExecutor) {
            discoveryExecutor.shutdownNow();
        }
    }

    private void notifyEndpoint(CameraEngine.CameraEndpoint endpoint) {
        if (listener == null || endpoint == null || isDestroyed()) {
            return;
        }
        try {
            listener.onEndpointDiscovered(endpoint);
        } catch (Exception error) {
            Log.e(TAG, "Endpoint callback failed", error);
        }
    }

    // ---------------------------------------------------------------------
    // SSDP discovery
    // ---------------------------------------------------------------------

    private CameraEngine.CameraEndpoint discoverViaSsdp(Network network, long deadline) {
        if (isDestroyed() || remainingMillis(deadline) <= 0L) {
            return null;
        }
        log("INFO", "Trying SSDP discovery");
        acquireMulticastLock();
        try {
            for (String searchTarget : Arrays.asList(SONY_SCALAR_ST, "ssdp:all")) {
                if (isDestroyed() || remainingMillis(deadline) <= 0L) {
                    return null;
                }
                long searchDeadline = Math.min(
                        deadline,
                        System.currentTimeMillis() + SSDP_TIMEOUT_MS / 2L + 100L
                );
                for (String location : ssdpSearch(network, searchTarget, searchDeadline)) {
                    if (isDestroyed() || remainingMillis(deadline) <= 0L) {
                        return null;
                    }
                    log("INFO", "SSDP LOCATION: " + location);
                    CameraEngine.CameraEndpoint endpoint =
                            endpointFromDescriptionLocation(network, location);
                    if (endpoint != null) {
                        log("INFO", "Camera discovered via SSDP");
                        return endpoint;
                    }
                }
            }
        } finally {
            releaseMulticastLock();
        }
        return null;
    }

    private List<String> ssdpSearch(Network network, String searchTarget, long deadline) {
        Set<String> locations = new LinkedHashSet<>();
        byte[] requestBytes = (
                "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: " + searchTarget + "\r\n" +
                "USER-AGENT: LANDCAM/1.0 Android\r\n\r\n"
        ).getBytes(StandardCharsets.UTF_8);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(250);
            try {
                network.bindSocket(socket);
            } catch (Exception bindError) {
                log("WARN", "Could not bind SSDP socket to camera network: "
                        + safeMessage(bindError));
            }

            InetAddress multicast = InetAddress.getByName(SSDP_ADDRESS);
            DatagramPacket request = new DatagramPacket(
                    requestBytes, requestBytes.length, multicast, SSDP_PORT
            );
            socket.send(request);
            sleepQuietly(50L);
            if (!isDestroyed() && remainingMillis(deadline) > 0L) {
                socket.send(request);
            }

            byte[] buffer = new byte[8192];
            while (!isDestroyed() && remainingMillis(deadline) > 0L) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String response = new String(
                            packet.getData(), packet.getOffset(), packet.getLength(),
                            StandardCharsets.UTF_8
                    );
                    Matcher matcher = SSDP_LOCATION_PATTERN.matcher(response);
                    while (matcher.find()) {
                        String location = matcher.group(1);
                        if (isValidHttpUrl(location)) {
                            locations.add(location.trim());
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                    // Poll again until the bounded search window closes.
                }
            }
        } catch (Exception error) {
            log("WARN", "SSDP search failed for " + searchTarget + ": "
                    + safeMessage(error));
        }
        return new ArrayList<>(locations);
    }

    private CameraEngine.CameraEndpoint endpointFromDescriptionLocation(
            Network network, String location) {
        if (isDestroyed() || !isValidHttpUrl(location)
                || !isLocalUrlHost(network, location)) {
            return null;
        }
        try {
            URL descriptionUrl = new URL(location.trim());
            String xml = readText(network, descriptionUrl, HTTP_PROBE_TIMEOUT_MS);
            if (xml == null || xml.trim().isEmpty()) {
                return null;
            }
            return endpointFromDescriptionXml(network, descriptionUrl, xml);
        } catch (Exception error) {
            log("WARN", "Description fetch failed: " + safeMessage(error));
            return null;
        }
    }

    private CameraEngine.CameraEndpoint endpointFromDescriptionXml(
            Network network, URL sourceUrl, String xml) {
        if (isDestroyed() || xml == null || xml.trim().isEmpty()) {
            return null;
        }

        String friendlyName = firstXmlValue(XML_FRIENDLY_NAME_PATTERN, xml);
        String modelName = firstXmlValue(XML_MODEL_NAME_PATTERN, xml);
        Set<String> resolvedApiUrls = new LinkedHashSet<>();

        String baseUrl = firstXmlValue(XML_BASE_URL_PATTERN, xml);
        addApiCandidates(sourceUrl, baseUrl, resolvedApiUrls);

        Matcher matcher = XML_ACTION_URL_PATTERN.matcher(xml);
        while (matcher.find()) {
            addApiCandidates(sourceUrl, cleanXmlValue(matcher.group(1)), resolvedApiUrls);
        }

        for (String apiUrl : resolvedApiUrls) {
            if (isDestroyed()) {
                return null;
            }
            if (!isValidHttpUrl(apiUrl) || !isLocalUrlHost(network, apiUrl)) {
                continue;
            }
            try {
                URL parsed = new URL(apiUrl);
                String host = parsed.getHost();
                if (host == null || host.trim().isEmpty()) {
                    continue;
                }
                String scheme = parsed.getProtocol().toLowerCase(Locale.US);
                int port = parsed.getPort() > 0
                        ? parsed.getPort()
                        : parsed.getDefaultPort();
                if (port <= 0) {
                    port = "https".equals(scheme) ? 443 : 80;
                }

                CameraEngine.CameraEndpoint endpoint = new CameraEngine.CameraEndpoint(
                        host, port, scheme, apiUrl,
                        safeText(friendlyName), safeText(modelName)
                );
                if (probeCameraApi(network, endpoint)) {
                    return endpoint;
                }
            } catch (Exception ignored) {
                // Continue through alternative API URLs from this description.
            }
        }
        return null;
    }

    private void addApiCandidates(URL sourceUrl, String rawUrl, Set<String> target) {
        if (sourceUrl == null || rawUrl == null || rawUrl.trim().isEmpty()) {
            return;
        }
        try {
            // Sony descriptions may contain absolute or relative action URLs.
            URL resolved = new URL(sourceUrl, cleanXmlValue(rawUrl));
            String resolvedValue = resolved.toExternalForm();
            for (String candidate : buildCameraApiCandidates(resolvedValue)) {
                if (isValidHttpUrl(candidate)) {
                    target.add(normalizeApiUrl(candidate));
                }
            }
        } catch (Exception ignored) {
            // Ignore malformed candidate; other candidates may still succeed.
        }
    }

    private CameraEngine.CameraEndpoint discoverViaDescription(
            Network network, long deadline) {
        if (isDestroyed() || remainingMillis(deadline) <= 0L) {
            return null;
        }
        log("INFO", "Trying direct device-description discovery");
        List<String> hosts = collectCandidateHosts(network);
        if (hosts.size() > MAX_DESCRIPTION_HOSTS) {
            hosts = new ArrayList<>(hosts.subList(0, MAX_DESCRIPTION_HOSTS));
        }
        if (hosts.isEmpty()) {
            return null;
        }

        CompletionService<CameraEngine.CameraEndpoint> completions =
                new ExecutorCompletionService<>(discoveryExecutor);
        List<Future<CameraEngine.CameraEndpoint>> futures = new ArrayList<>();
        for (String host : hosts) {
            if (isDestroyed() || remainingMillis(deadline) <= 0L) {
                break;
            }
            futures.add(completions.submit(() -> probeHostDescriptions(network, host, deadline)));
        }

        int completed = 0;
        try {
            while (!isDestroyed() && completed < futures.size()
                    && remainingMillis(deadline) > 0L) {
                Future<CameraEngine.CameraEndpoint> future = completions.poll(
                        Math.min(200L, remainingMillis(deadline)), TimeUnit.MILLISECONDS
                );
                if (future == null) {
                    continue;
                }
                completed++;
                try {
                    CameraEngine.CameraEndpoint endpoint = future.get();
                    if (endpoint != null) {
                        cancelOutstanding(futures);
                        log("INFO", "Camera discovered via direct description");
                        return endpoint;
                    }
                } catch (Exception ignored) {
                    // A failed host is not a failed discovery operation.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            cancelOutstanding(futures);
        }
        return null;
    }

    private CameraEngine.CameraEndpoint probeHostDescriptions(
            Network network, String host, long deadline) {
        for (int port : SONY_DESCRIPTION_PORTS) {
            if (isDestroyed() || Thread.currentThread().isInterrupted()
                    || remainingMillis(deadline) <= 0L) {
                return null;
            }
            for (String path : DESCRIPTION_PATHS) {
                if (isDestroyed() || Thread.currentThread().isInterrupted()
                        || remainingMillis(deadline) <= 0L) {
                    return null;
                }
                try {
                    URL url = new URL("http", host, port, path);
                    String xml = readText(network, url, HTTP_PROBE_TIMEOUT_MS);
                    if (xml == null || xml.trim().isEmpty()) {
                        continue;
                    }
                    CameraEngine.CameraEndpoint endpoint =
                            endpointFromDescriptionXml(network, url, xml);
                    if (endpoint != null) {
                        return endpoint;
                    }
                } catch (Exception ignored) {
                    // Try the next description path/port.
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Bounded host/API scan
    // ---------------------------------------------------------------------

    private CameraEngine.CameraEndpoint discoverViaNetworkScan(
            Network network, long deadline) {
        if (isDestroyed() || remainingMillis(deadline) <= 0L) {
            return null;
        }
        List<String> hosts = collectFullScanHosts(network);
        if (hosts.isEmpty()) {
            return null;
        }
        log("INFO", "Trying network endpoint scan: " + hosts.size() + " hosts");

        CompletionService<CameraEngine.CameraEndpoint> completions =
                new ExecutorCompletionService<>(discoveryExecutor);
        List<Future<CameraEngine.CameraEndpoint>> futures = new ArrayList<>();
        for (String host : hosts) {
            if (isDestroyed() || remainingMillis(deadline) <= 0L) {
                break;
            }
            futures.add(completions.submit(() -> probeHostForCamera(network, host, deadline)));
        }

        int completed = 0;
        try {
            while (!isDestroyed() && completed < futures.size()
                    && remainingMillis(deadline) > 0L) {
                Future<CameraEngine.CameraEndpoint> future = completions.poll(
                        Math.min(250L, remainingMillis(deadline)), TimeUnit.MILLISECONDS
                );
                if (future == null) {
                    continue;
                }
                completed++;
                try {
                    CameraEngine.CameraEndpoint endpoint = future.get();
                    if (endpoint != null) {
                        cancelOutstanding(futures);
                        return endpoint;
                    }
                } catch (Exception ignored) {
                    // Continue checking other hosts.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            cancelOutstanding(futures);
        }
        return null;
    }

    private CameraEngine.CameraEndpoint probeHostForCamera(
            Network network, String host, long deadline) {
        if (isDestroyed() || Thread.currentThread().isInterrupted()) {
            return null;
        }
        for (int port : SONY_API_PORTS) {
            if (isDestroyed() || Thread.currentThread().isInterrupted()
                    || remainingMillis(deadline) <= 0L) {
                return null;
            }
            if (!probeTcp(network, host, port, TCP_PROBE_TIMEOUT_MS)) {
                continue;
            }
            String scheme = port == 443 ? "https" : "http";
            for (String apiPath : Arrays.asList("/sony/camera", "/camera", "/sony")) {
                if (isDestroyed() || Thread.currentThread().isInterrupted()
                        || remainingMillis(deadline) <= 0L) {
                    return null;
                }
                String apiUrl = scheme + "://" + host + ":" + port + apiPath;
                CameraEngine.CameraEndpoint endpoint = new CameraEngine.CameraEndpoint(
                        host, port, scheme, apiUrl, "", ""
                );
                if (probeCameraApi(network, endpoint)) {
                    return endpoint;
                }
            }
        }
        return null;
    }

    private boolean probeTcp(Network network, String host, int port, int timeoutMs) {
        if (network == null || host == null || host.trim().isEmpty()) {
            return false;
        }
        try (Socket socket = new Socket()) {
            try {
                network.bindSocket(socket);
            } catch (Exception bindError) {
                log("WARN", "TCP probe network bind failed: " + safeMessage(bindError));
            }
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Validates a Sony-style JSON-RPC endpoint without starting a camera session. */
    private boolean probeCameraApi(Network network, CameraEngine.CameraEndpoint endpoint) {
        if (isDestroyed() || network == null || endpoint == null
                || !isValidHttpUrl(endpoint.apiUrl)
                || !isLocalUrlHost(network, endpoint.apiUrl)) {
            return false;
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint.apiUrl);
            URLConnection raw = network.openConnection(url);
            if (!(raw instanceof HttpURLConnection)) {
                return false;
            }
            connection = (HttpURLConnection) raw;
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(API_PROBE_TIMEOUT_MS);
            connection.setReadTimeout(API_PROBE_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");

            JSONObject request = new JSONObject();
            request.put("method", "getApplicationInfo");
            request.put("params", new JSONArray());
            request.put("id", 1);
            request.put("version", "1.0");
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
                output.flush();
            }

            int code = connection.getResponseCode();
            if (code < 200 || code >= 600 || (code >= 300 && code < 400)) {
                return false;
            }
            InputStream input = code >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            if (input == null) {
                return false;
            }
            String response = readText(input);
            if (response == null || response.trim().isEmpty()) {
                return false;
            }
            JSONObject json = new JSONObject(response);
            return json.has("result") || json.has("error");
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Network address candidates
    // ---------------------------------------------------------------------

    private List<String> collectCandidateHosts(Network network) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        LinkProperties properties = getLinkProperties(network);
        if (properties != null) {
            for (RouteInfo route : properties.getRoutes()) {
                InetAddress gateway = route.getGateway();
                if (gateway instanceof Inet4Address && isUsableLocalAddress(gateway)) {
                    candidates.add(gateway.getHostAddress());
                }
            }
            for (LinkAddress linkAddress : properties.getLinkAddresses()) {
                if (!(linkAddress.getAddress() instanceof Inet4Address)) {
                    continue;
                }
                Inet4Address local = (Inet4Address) linkAddress.getAddress();
                if (isUsableLocalAddress(local)) {
                    addNearbyHosts(candidates, local, linkAddress.getPrefixLength(), 8);
                }
            }
        }
        addFallbackHosts(candidates);
        return new ArrayList<>(candidates);
    }

    private List<String> collectFullScanHosts(Network network) {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        LinkProperties properties = getLinkProperties(network);
        if (properties != null) {
            for (LinkAddress linkAddress : properties.getLinkAddresses()) {
                if (!(linkAddress.getAddress() instanceof Inet4Address)) {
                    continue;
                }
                Inet4Address local = (Inet4Address) linkAddress.getAddress();
                if (isUsableLocalAddress(local)) {
                    addSubnetHosts(hosts, local, linkAddress.getPrefixLength());
                }
                if (hosts.size() >= DISCOVERY_MAX_HOSTS) {
                    break;
                }
            }
            for (RouteInfo route : properties.getRoutes()) {
                InetAddress gateway = route.getGateway();
                if (gateway instanceof Inet4Address && isUsableLocalAddress(gateway)) {
                    hosts.add(gateway.getHostAddress());
                }
            }
        }
        if (hosts.isEmpty()) {
            addSubnetHosts(hosts, parseIpv4("192.168.122.10"), 24);
            addSubnetHosts(hosts, parseIpv4("192.168.1.10"), 24);
            addSubnetHosts(hosts, parseIpv4("192.168.0.10"), 24);
        }
        List<String> result = new ArrayList<>(hosts);
        if (result.size() > DISCOVERY_MAX_HOSTS) {
            return new ArrayList<>(result.subList(0, DISCOVERY_MAX_HOSTS));
        }
        return result;
    }

    private void addFallbackHosts(Set<String> target) {
        Collections.addAll(target,
                "192.168.122.1", "192.168.1.1", "192.168.0.1",
                "192.168.2.1", "10.0.0.1", "10.0.0.2", "169.254.1.1");
    }

    private void addNearbyHosts(Set<String> target, Inet4Address local,
                                int prefixLength, int radius) {
        if (local == null) {
            return;
        }
        int value = ipv4ToInt(local.getAddress());
        int safePrefix = Math.max(0, Math.min(30, prefixLength));
        int mask = safePrefix == 0 ? 0 : -1 << (32 - safePrefix);
        int networkPart = value & mask;
        int hostValue = value & ~mask;
        for (int delta = -radius; delta <= radius; delta++) {
            int candidate = hostValue + delta;
            if (candidate < 1 || candidate > 254) {
                continue;
            }
            target.add(intToIpv4(networkPart | candidate));
            if (target.size() >= DISCOVERY_MAX_HOSTS) {
                return;
            }
        }
        target.add(intToIpv4(networkPart | 1));
    }

    private void addSubnetHosts(Set<String> target, Inet4Address local, int prefixLength) {
        if (local == null || target.size() >= DISCOVERY_MAX_HOSTS) {
            return;
        }
        int safePrefix = Math.max(0, Math.min(30, prefixLength));
        if (safePrefix < 24) {
            addNearbyHosts(target, local, safePrefix, 126);
            return;
        }
        int value = ipv4ToInt(local.getAddress());
        int mask = -1 << (32 - safePrefix);
        int networkPart = value & mask;
        int hostCount = 1 << (32 - safePrefix);
        int usableCount = Math.min(hostCount - 2, DISCOVERY_MAX_HOSTS);
        for (int host = 1; host <= usableCount; host++) {
            target.add(intToIpv4(networkPart | host));
            if (target.size() >= DISCOVERY_MAX_HOSTS) {
                return;
            }
        }
    }

    // ---------------------------------------------------------------------
    // URL and XML helpers
    // ---------------------------------------------------------------------

    private List<String> buildCameraApiCandidates(String actionUrl) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (actionUrl == null || actionUrl.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String base = normalizeApiUrl(actionUrl);
        if (base == null) {
            return new ArrayList<>();
        }
        String lower = base.toLowerCase(Locale.US);
        if (lower.endsWith("/sony/camera")) {
            candidates.add(base);
        } else if (lower.endsWith("/sony")) {
            candidates.add(base + "/camera");
            candidates.add(base);
        } else if (lower.endsWith("/camera")) {
            candidates.add(base);
        } else {
            candidates.add(base + "/camera");
            candidates.add(base + "/sony/camera");
            candidates.add(base);
        }
        return new ArrayList<>(candidates);
    }

    private String normalizeApiUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            return null;
        }
        String value = apiUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private boolean isValidHttpUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        try {
            URL url = new URL(value.trim());
            String scheme = url.getProtocol();
            String host = url.getHost();
            int port = url.getPort();
            boolean validPort = port == -1 || (port >= 1 && port <= 65535);
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && host != null && !host.trim().isEmpty()
                    && url.getUserInfo() == null
                    && validPort;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Resolve DNS using the selected network and reject non-local endpoints. */
    private boolean isLocalUrlHost(Network network, String value) {
        if (network == null || !isValidHttpUrl(value)) {
            return false;
        }
        try {
            String host = new URL(value.trim()).getHost();
            InetAddress[] addresses = network.getAllByName(host);
            if (addresses == null || addresses.length == 0) {
                return false;
            }
            // Reject mixed public/private DNS answers as well as public-only hosts.
            // This prevents a device-description URL from redirecting discovery
            // traffic to an unrelated public address.
            for (InetAddress address : addresses) {
                if (!isPrivateOrLinkLocal(address)) {
                    return false;
                }
            }
            return addresses.length > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isPrivateOrLinkLocal(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address) {
            byte[] bytes = address.getAddress();
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            return a == 10
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 169 && b == 254);
        }
        return address.isSiteLocalAddress() || address.isLinkLocalAddress();
    }

    private String firstXmlValue(Pattern pattern, String xml) {
        if (pattern == null || xml == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(xml);
        return matcher.find() ? cleanXmlValue(matcher.group(1)) : null;
    }

    private String cleanXmlValue(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("<![CDATA[", "")
                .replace("]]>", "")
                .trim()
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        return result.length() > 256 ? result.substring(0, 256) : result;
    }

    // ---------------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------------

    private String readText(Network network, URL url, int timeoutMs) throws Exception {
        if (isDestroyed()) {
            return null;
        }
        URLConnection raw = network.openConnection(url);
        if (!(raw instanceof HttpURLConnection)) {
            throw new IllegalStateException("URL IS NOT HTTP");
        }
        HttpURLConnection connection = (HttpURLConnection) raw;
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setUseCaches(false);
            // Do not follow redirect targets supplied by a device description.
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/xml,text/xml,text/plain,*/*");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            InputStream input = connection.getInputStream();
            return input == null ? null : readText(input);
        } finally {
            connection.disconnect();
        }
    }

    private String readText(InputStream input) throws Exception {
        try (InputStream in = new BufferedInputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while (!isDestroyed() && (count = in.read(buffer)) != -1) {
                if (count == 0) {
                    continue;
                }
                if (output.size() + count > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("HTTP RESPONSE TOO LARGE");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    // ---------------------------------------------------------------------
    // Android/network/resource helpers
    // ---------------------------------------------------------------------

    private LinkProperties getLinkProperties(Network network) {
        if (network == null || connectivityManager == null) {
            return null;
        }
        try {
            return connectivityManager.getLinkProperties(network);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void acquireMulticastLock() {
        if (wifiManager == null || isDestroyed()) {
            return;
        }
        synchronized (multicastLockGuard) {
            try {
                if (multicastLock == null) {
                    multicastLock = wifiManager.createMulticastLock(TAG);
                    multicastLock.setReferenceCounted(false);
                }
                if (!multicastLock.isHeld()) {
                    multicastLock.acquire();
                }
            } catch (Exception error) {
                log("WARN", "Multicast lock failed: " + safeMessage(error));
            }
        }
    }

    private void releaseMulticastLock() {
        synchronized (multicastLockGuard) {
            WifiManager.MulticastLock lock = multicastLock;
            multicastLock = null;
            if (lock == null) {
                return;
            }
            try {
                if (lock.isHeld()) {
                    lock.release();
                }
            } catch (Exception ignored) {
                // Android may already have released the lock during process teardown.
            }
        }
    }

    private boolean isDestroyed() {
        return destroyed.get();
    }

    private boolean isUsableLocalAddress(InetAddress address) {
        // Discovery is deliberately limited to private/link-local endpoints.
        // It should not launch a subnet sweep over a public IPv4 network.
        return isPrivateOrLinkLocal(address);
    }

    private static Inet4Address parseIpv4(String host) {
        if (host == null) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address instanceof Inet4Address ? (Inet4Address) address : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int ipv4ToInt(byte[] bytes) {
        return ((bytes[0] & 0xff) << 24)
                | ((bytes[1] & 0xff) << 16)
                | ((bytes[2] & 0xff) << 8)
                | (bytes[3] & 0xff);
    }

    private static String intToIpv4(int value) {
        return ((value >>> 24) & 0xff) + "."
                + ((value >>> 16) & 0xff) + "."
                + ((value >>> 8) & 0xff) + "."
                + (value & 0xff);
    }

    private static long remainingMillis(long deadline) {
        return Math.max(0L, deadline - System.currentTimeMillis());
    }

    private static void cancelOutstanding(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "UNKNOWN ERROR";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private void log(String level, String message) {
        String normalizedLevel = level == null ? "INFO" : level.toUpperCase(Locale.US);
        String text = "[" + normalizedLevel + "] " + (message == null ? "" : message);
        if ("ERROR".equals(normalizedLevel)) {
            Log.e(TAG, text);
        } else if ("WARN".equals(normalizedLevel)) {
            Log.w(TAG, text);
        } else {
            Log.i(TAG, text);
        }
    }
}
