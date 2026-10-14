package com.liskovsoft.smartyoutubetv2.common.misc.cast;

import android.os.SystemClock;

import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the devices of the local network that have a YouTube app.<br/>
 * <br/>
 * Smart TVs, Fire TV sticks, consoles (DIAL protocol):<br/>
 * 1) a search request to everybody (SSDP). The devices answer with the address of their description.<br/>
 * 2) the description has the name of the device and the address of its apps<br/>
 * 3) [address of the apps]/YouTube tells if the app is running and what its screen id is. The app can be started there too.<br/>
 * <br/>
 * Chromecast, Google TV, Android TV:<br/>
 * 1) they answer the question "who is a Chromecast?" (mDNS) and most of them the search above too<br/>
 * 2) the description has the name, but there's no YouTube among the apps: it's behind the cast channel of the device
 * (see {@link CastChannel})<br/>
 * <br/>
 * NOTE: all the methods block. Don't call them from the main thread.
 */
public class DialDiscovery {
    private static final String TAG = DialDiscovery.class.getSimpleName();
    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String SEARCH_TARGET = "urn:dial-multiscreen-org:service:dial:1";
    private static final String SEARCH_REQUEST =
            "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: " + SEARCH_TARGET + "\r\n\r\n";
    private static final String MDNS_ADDRESS = "224.0.0.251";
    private static final int MDNS_PORT = 5353;
    private static final String CAST_SERVICE = "_googlecast._tcp.local";
    /**
     * Where a Chromecast-like device describes itself
     */
    private static final String CAST_DESCRIPTION = ":8008/ssdp/device-desc.xml";
    /**
     * Fire TV answers the question about its YouTube app only to YouTube (403 otherwise)
     */
    private static final Map<String, String> DIAL_HEADERS = Collections.singletonMap("Origin", "https://www.youtube.com");
    private static final String APP_NAME = "YouTube";
    private static final Pattern LOCATION = Pattern.compile("(?im)^location:\\s*(\\S+)\\s*$");
    private static final Pattern FRIENDLY_NAME = Pattern.compile("<friendlyName>([^<]*)</friendlyName>");
    private static final Pattern SCREEN_ID = Pattern.compile("<screenId>([^<]+)</screenId>");
    private static final long START_TIMEOUT_MS = 25_000;
    private static final long START_POLL_MS = 1_500;
    private static final int MAX_PARALLEL_REQUESTS = 6;

    /**
     * @return the devices that have the YouTube app (running or not). Empty if nothing is found.
     */
    public static List<CastDevice> discover(int timeoutMs) {
        List<CastDevice> result = new ArrayList<>();
        Set<String> locations = search(timeoutMs);

        if (locations.isEmpty()) {
            return result;
        }

        // All at once: a device that is slow to answer doesn't hold up the others
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(locations.size(), MAX_PARALLEL_REQUESTS));

        try {
            List<Future<CastDevice>> devices = new ArrayList<>();

            for (String location : locations) {
                devices.add(executor.submit(() -> describe(location)));
            }

            for (Future<CastDevice> future : devices) {
                try {
                    CastDevice device = future.get();

                    if (device != null) {
                        result.add(device);
                    }
                } catch (ExecutionException e) {
                    Log.e(TAG, "Can't read the description of a device: %s", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            executor.shutdownNow();
        }

        Collections.sort(result, (left, right) -> left.getTitle().compareToIgnoreCase(right.getTitle()));

        return result;
    }

    /**
     * Makes sure that the app of the device is running.
     * @return its screen id
     */
    public static String startApp(CastDevice device) throws IOException {
        String url = device.appUrl + APP_NAME;
        String screenId = parseScreenId(CastHttp.get(url, DIAL_HEADERS).body);

        if (screenId != null) {
            return screenId;
        }

        // NOTE: the app adds the code to its url. Any unique value is fine: the screen id is read back below.
        CastHttp.Response response = CastHttp.post(url, "pairingCode=" + UUID.randomUUID(), "text/plain; charset=utf-8", DIAL_HEADERS);

        if (response.code >= 400) {
            throw new IOException("The device refused to start YouTube (" + response.code + ")");
        }

        long deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS;

        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(START_POLL_MS);

            screenId = parseScreenId(CastHttp.get(url, DIAL_HEADERS).body);

            if (screenId != null) {
                return screenId;
            }
        }

        throw new IOException("YouTube didn't start on the device");
    }

    /**
     * @return the addresses of the descriptions of the devices
     */
    private static Set<String> search(int timeoutMs) {
        Set<String> locations = new HashSet<>();

        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] request = SEARCH_REQUEST.getBytes(StandardCharsets.UTF_8);
            DatagramPacket dialSearch = new DatagramPacket(request, request.length, InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT);
            byte[] question = getCastQuestion();
            DatagramPacket castSearch = new DatagramPacket(question, question.length, InetAddress.getByName(MDNS_ADDRESS), MDNS_PORT);

            // UDP: a packet could be lost
            for (int i = 0; i < 2; i++) {
                socket.send(dialSearch);

                try {
                    socket.send(castSearch);
                } catch (IOException | RuntimeException e) {
                    // A network that doesn't pass it: the other search finds most of the devices as well
                    Log.e(TAG, "Cast search error: %s", e.getMessage());
                }
            }

            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            byte[] buffer = new byte[4 * 1024];

            while (true) {
                long left = deadline - SystemClock.elapsedRealtime();

                if (left <= 0) {
                    break;
                }

                socket.setSoTimeout((int) left);
                DatagramPacket answer = new DatagramPacket(buffer, buffer.length);

                try {
                    socket.receive(answer);
                } catch (SocketTimeoutException e) {
                    break;
                }

                if (answer.getPort() == MDNS_PORT) {
                    // Who answers is a Chromecast: its name and its apps are asked for later
                    locations.add("http://" + answer.getAddress().getHostAddress() + CAST_DESCRIPTION);
                    continue;
                }

                String location = parseLocation(new String(answer.getData(), 0, answer.getLength(), StandardCharsets.UTF_8));

                if (location != null) {
                    locations.add(location);
                }
            }
        } catch (IOException | RuntimeException e) {
            // No network, e.g.
            Log.e(TAG, "Search error: %s", e.getMessage());
        }

        return locations;
    }

    /**
     * The question "who is a Chromecast?": a DNS question for the pointers of the service, the answers are wanted directly
     */
    private static byte[] getCastQuestion() {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        // The header: no id, no flags, one question
        result.write(new byte[] {0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0}, 0, 12);

        for (String label : CAST_SERVICE.split("\\.")) {
            byte[] data = label.getBytes(StandardCharsets.US_ASCII);
            result.write(data.length);
            result.write(data, 0, data.length);
        }

        // The end of the name, type: pointer, class: internet (and "answer to me, not to everybody")
        result.write(new byte[] {0, 0, 12, (byte) 0x80, 1}, 0, 5);

        return result.toByteArray();
    }

    private static CastDevice describe(String location) throws IOException {
        // NOTE: the answers come from the local network. Still: nothing but plain http, like the search promises.
        if (!location.startsWith("http://")) {
            return null;
        }

        CastHttp.Response response = CastHttp.get(location, DIAL_HEADERS);
        String name = parseFriendlyName(response.body);
        String appUrl = response.header("Application-URL");

        if (appUrl != null && appUrl.startsWith("http://")) {
            if (!appUrl.endsWith("/")) {
                appUrl += "/";
            }

            CastHttp.Response app = CastHttp.get(appUrl + APP_NAME, DIAL_HEADERS);

            if (app.code == 200) {
                return new CastDevice(name, parseScreenId(app.body), appUrl);
            }
        }

        // No YouTube app the DIAL way. A Chromecast-like device has it behind its cast channel.
        if (location.endsWith(CAST_DESCRIPTION) && response.code == 200) {
            String host = new URL(location).getHost();
            boolean hasYouTube;

            try {
                hasYouTube = CastChannel.hasYouTube(host);
            } catch (IOException e) {
                // Busy or just waking up: the device is there, the answer comes when it's used
                Log.e(TAG, "No answer on the cast channel of %s: %s", host, e.getMessage());
                hasYouTube = true;
            }

            if (hasYouTube) {
                CastDevice device = new CastDevice(name, null, null);
                device.castHost = host;
                return device;
            }
        }

        return null;
    }

    static String parseLocation(String ssdpAnswer) {
        Matcher matcher = LOCATION.matcher(ssdpAnswer);
        return matcher.find() ? matcher.group(1) : null;
    }

    static String parseFriendlyName(String description) {
        Matcher matcher = FRIENDLY_NAME.matcher(description != null ? description : "");
        return matcher.find() ? unescape(matcher.group(1).trim()) : null;
    }

    static String parseScreenId(String appInfo) {
        Matcher matcher = SCREEN_ID.matcher(appInfo != null ? appInfo : "");
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String unescape(String xml) {
        return xml.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }
}
