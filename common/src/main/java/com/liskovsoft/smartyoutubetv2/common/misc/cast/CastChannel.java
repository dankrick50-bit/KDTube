package com.liskovsoft.smartyoutubetv2.common.misc.cast;

import android.annotation.SuppressLint;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The Chromecast way of talking to a device (Chromecast, Google TV, Android TV, Nest Hub).<br/>
 * These devices don't offer their YouTube app the DIAL way (see {@link DialDiscovery}): the app has to be asked for
 * through the cast channel of the device. Only what's needed for that is here:<br/>
 * 1) is there a YouTube app?<br/>
 * 2) start it (if it isn't running)<br/>
 * 3) ask it for its screen id<br/>
 * The video itself is started the same way as on every other screen (see {@link LoungeSender}).<br/>
 * <br/>
 * The channel: TLS to port 8009, the messages are a length (4 bytes) and a small protobuf (CastMessage).<br/>
 * <br/>
 * NOTE: all the methods block. Don't call them from the main thread.
 */
final class CastChannel implements Closeable {
    private static final int PORT = 8009;
    private static final String YOUTUBE_APP_ID = "233637DE";
    private static final String SENDER = "sender-kdtube";
    private static final String RECEIVER = "receiver-0";
    private static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_YOUTUBE = "urn:x-cast:com.google.youtube.mdx";
    private static final int MAX_MESSAGE_BYTES = 256 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int PROBE_TIMEOUT_MS = 5_000;
    private static final int START_TIMEOUT_MS = 30_000;
    private static final int SCREEN_ID_TRIES = 12;
    private static final int SCREEN_ID_WAIT_MS = 2_500;
    private final SSLSocket mSocket;
    private final DataInputStream mInput;
    private final OutputStream mOutput;

    private interface Test {
        boolean accept(String namespace, JSONObject payload);
    }

    /**
     * The devices identify themselves with a certificate of their own making, like every Chromecast does.
     * Nothing secret is sent: the question for the YouTube app and its public screen id.
     */
    @SuppressLint({"CustomX509TrustManager", "TrustAllX509TrustManager"})
    private static final class DeviceTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private CastChannel(String host) throws IOException {
        Socket plain = new Socket();

        try {
            plain.connect(new InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new DeviceTrustManager()}, null);

            mSocket = (SSLSocket) context.getSocketFactory().createSocket(plain, host, PORT, true);
            mSocket.setEnabledProtocols(getModernProtocols(mSocket.getSupportedProtocols()));
            mSocket.setSoTimeout(CONNECT_TIMEOUT_MS);
            mSocket.startHandshake();
            mInput = new DataInputStream(mSocket.getInputStream());
            mOutput = mSocket.getOutputStream();
        } catch (GeneralSecurityException | RuntimeException e) {
            close(plain);
            throw new IOException("Can't reach the device (" + e.getMessage() + ")", e);
        } catch (IOException e) {
            close(plain);
            throw new IOException("Can't reach the device (" + e.getMessage() + ")", e);
        }

        send(RECEIVER, NS_CONNECTION, message("CONNECT"));
    }

    /**
     * Changes nothing on the device
     * @return true if the device is able to play YouTube (a speaker isn't, e.g.)
     */
    static boolean hasYouTube(String host) throws IOException {
        try (CastChannel channel = new CastChannel(host)) {
            long deadline = SystemClock.elapsedRealtime() + PROBE_TIMEOUT_MS;

            channel.send(RECEIVER, NS_RECEIVER, message("GET_APP_AVAILABILITY").put("appId", new JSONArray().put(YOUTUBE_APP_ID)).put("requestId", 1));

            JSONObject answer = channel.waitFor((namespace, payload) -> NS_RECEIVER.equals(namespace) && payload.optInt("requestId") == 1, deadline);
            JSONObject availability = answer != null ? answer.optJSONObject("availability") : null;

            if (availability == null) {
                throw new IOException("The device didn't answer in time");
            }

            return "APP_AVAILABLE".equals(availability.optString(YOUTUBE_APP_ID));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /**
     * Starts the YouTube app of the device (if it isn't running)
     * @return its screen id
     */
    static String getScreenId(String host) throws IOException {
        try (CastChannel channel = new CastChannel(host)) {
            long deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS;

            channel.send(RECEIVER, NS_RECEIVER, message("GET_STATUS").put("requestId", 1));

            JSONObject status = channel.waitFor((namespace, payload) ->
                    NS_RECEIVER.equals(namespace) && "RECEIVER_STATUS".equals(payload.optString("type")) && payload.optInt("requestId") == 1, deadline);

            if (status == null) {
                throw new IOException("The device didn't answer in time");
            }

            JSONObject app = findYouTube(status);

            if (app == null) {
                channel.send(RECEIVER, NS_RECEIVER, message("LAUNCH").put("appId", YOUTUBE_APP_ID).put("requestId", 2));

                // The answer to the request, or the news that the app is there now (some devices answer with the old state first)
                JSONObject answer = channel.waitFor((namespace, payload) -> NS_RECEIVER.equals(namespace) &&
                        ("LAUNCH_ERROR".equals(payload.optString("type")) || findYouTube(payload) != null), deadline);

                if (answer == null) {
                    throw new IOException("YouTube didn't start on the device");
                }

                if ("LAUNCH_ERROR".equals(answer.optString("type"))) {
                    throw new IOException("The device can't start YouTube (" + answer.optString("reason", "no reason given") + ")");
                }

                app = findYouTube(answer);
            }

            String transportId = app.optString("transportId");

            channel.send(transportId, NS_CONNECTION, message("CONNECT"));

            // The app needs a moment after its start: ask until it knows its id
            for (int i = 0; i < SCREEN_ID_TRIES; i++) {
                channel.send(transportId, NS_YOUTUBE, message("getMdxSessionStatus"));

                JSONObject answer = channel.waitFor((namespace, payload) ->
                        NS_YOUTUBE.equals(namespace) && "mdxSessionStatus".equals(payload.optString("type")), SystemClock.elapsedRealtime() + SCREEN_ID_WAIT_MS);
                JSONObject data = answer != null ? answer.optJSONObject("data") : null;
                String screenId = data != null ? data.optString("screenId") : "";

                if (!screenId.isEmpty()) {
                    return screenId;
                }
            }

            throw new IOException("The YouTube app of the device didn't say how to reach it");
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void close() {
        close(mSocket);
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException | RuntimeException e) {
            // Closed already
        }
    }

    private static String[] getModernProtocols(String[] supported) {
        List<String> result = new ArrayList<>();

        for (String protocol : supported) {
            if (protocol.startsWith("TLS")) {
                result.add(protocol);
            }
        }

        return result.isEmpty() ? supported : result.toArray(new String[0]);
    }

    private static JSONObject message(String type) {
        try {
            return new JSONObject().put("type", type);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @param receiverStatus {status: {applications: [{appId, transportId...}]}}
     */
    private static JSONObject findYouTube(JSONObject receiverStatus) {
        JSONObject status = receiverStatus.optJSONObject("status");
        JSONArray applications = status != null ? status.optJSONArray("applications") : null;

        for (int i = 0; applications != null && i < applications.length(); i++) {
            JSONObject app = applications.optJSONObject(i);

            if (app != null && YOUTUBE_APP_ID.equals(app.optString("appId"))) {
                return app;
            }
        }

        return null;
    }

    /**
     * @return the first coming message that is accepted, null if there's no such message before the deadline
     */
    private JSONObject waitFor(Test test, long deadline) throws IOException {
        while (true) {
            long left = deadline - SystemClock.elapsedRealtime();

            if (left <= 0) {
                return null;
            }

            mSocket.setSoTimeout((int) left);

            String[] message;

            try {
                message = read();
            } catch (SocketTimeoutException e) {
                return null;
            }

            JSONObject payload;

            try {
                payload = new JSONObject(message[1]);
            } catch (JSONException e) {
                // A binary message (the device proving who it is): not needed here
                continue;
            }

            if (NS_HEARTBEAT.equals(message[0]) && "PING".equals(payload.optString("type"))) {
                send(RECEIVER, NS_HEARTBEAT, message("PONG"));
                continue;
            }

            if (test.accept(message[0], payload)) {
                return payload;
            }
        }
    }

    /**
     * CastMessage: 1 protocol version, 2 source, 3 destination, 4 namespace, 5 payload type (0: text), 6 the text
     */
    private void send(String destination, String namespace, JSONObject payload) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        writeVarint(message, 1 << 3);
        writeVarint(message, 0);
        writeText(message, 2, SENDER);
        writeText(message, 3, destination);
        writeText(message, 4, namespace);
        writeVarint(message, 5 << 3);
        writeVarint(message, 0);
        writeText(message, 6, payload.toString());

        int length = message.size();
        ByteArrayOutputStream packet = new ByteArrayOutputStream(length + 4);
        packet.write(length >>> 24);
        packet.write(length >>> 16);
        packet.write(length >>> 8);
        packet.write(length);
        message.writeTo(packet);

        mOutput.write(packet.toByteArray());
        mOutput.flush();
    }

    /**
     * @return {namespace, text}
     */
    private String[] read() throws IOException {
        int length = mInput.readInt();

        if (length < 0 || length > MAX_MESSAGE_BYTES) {
            throw new IOException("Unexpected answer of the device");
        }

        byte[] data = new byte[length];
        mInput.readFully(data);

        String namespace = "";
        String text = "";
        int[] position = {0};

        while (position[0] < data.length) {
            int tag = (int) readVarint(data, position);
            int field = tag >>> 3;
            int type = tag & 7;

            if (type == 0) {
                readVarint(data, position);
            } else if (type == 2) {
                int size = (int) readVarint(data, position);

                if (size < 0 || size > data.length - position[0]) {
                    break;
                }

                if (field == 4) {
                    namespace = new String(data, position[0], size, StandardCharsets.UTF_8);
                } else if (field == 6) {
                    text = new String(data, position[0], size, StandardCharsets.UTF_8);
                }

                position[0] += size;
            } else if (type == 5) {
                position[0] += 4;
            } else if (type == 1) {
                position[0] += 8;
            } else {
                // Not a message of this protocol
                break;
            }
        }

        return new String[] {namespace, text};
    }

    private static void writeVarint(ByteArrayOutputStream output, int value) {
        int rest = value;

        while (rest > 127) {
            output.write((rest & 127) | 128);
            rest >>>= 7;
        }

        output.write(rest);
    }

    private static void writeText(ByteArrayOutputStream output, int field, String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(output, (field << 3) | 2);
        writeVarint(output, data.length);
        output.write(data, 0, data.length);
    }

    private static long readVarint(byte[] data, int[] position) {
        long value = 0;
        int shift = 0;

        while (position[0] < data.length && shift < 64) {
            int next = data[position[0]++] & 0xFF;
            value |= (long) (next & 127) << shift;

            if (next < 128) {
                break;
            }

            shift += 7;
        }

        return value;
    }
}
