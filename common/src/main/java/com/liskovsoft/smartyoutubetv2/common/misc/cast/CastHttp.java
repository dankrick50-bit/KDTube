package com.liskovsoft.smartyoutubetv2.common.misc.cast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A few small requests: the http client of the platform is enough
 */
final class CastHttp {
    private static final int TIMEOUT_MS = 8_000;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    static final class Response {
        int code;
        String body;
        HttpURLConnection connection;

        String header(String name) {
            return connection.getHeaderField(name);
        }
    }

    private CastHttp() {
    }

    static Response get(String url) throws IOException {
        return request("GET", url, null, null, null);
    }

    static Response get(String url, Map<String, String> headers) throws IOException {
        return request("GET", url, null, null, headers);
    }

    static Response post(String url, String body, String contentType, Map<String, String> headers) throws IOException {
        return request("POST", url, body, contentType, headers);
    }

    static String form(String... keyValues) {
        StringBuilder result = new StringBuilder();

        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (result.length() > 0) {
                result.append('&');
            }

            result.append(encode(keyValues[i])).append('=').append(encode(keyValues[i + 1]));
        }

        return result.toString();
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value != null ? value : "", "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Response request(String method, String url, String body, String contentType, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();

        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(true);

            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }

            if (body != null) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(data.length);
                connection.setRequestProperty("Content-Type", contentType);

                try (OutputStream output = connection.getOutputStream()) {
                    output.write(data);
                }
            }

            Response response = new Response();
            response.connection = connection;
            response.code = connection.getResponseCode();

            InputStream input = response.code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            response.body = input != null ? read(input) : "";

            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();

        try (InputStream stream = input) {
            byte[] buffer = new byte[8 * 1024];

            for (int read; result.size() < MAX_RESPONSE_BYTES && (read = stream.read(buffer)) > 0; ) {
                result.write(buffer, 0, read);
            }
        } catch (java.net.SocketTimeoutException e) {
            // A stream that is kept open by the server: what has come is the answer
        }

        return new String(result.toByteArray(), StandardCharsets.UTF_8);
    }
}
