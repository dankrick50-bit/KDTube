package com.liskovsoft.googlecommon.common.converters.jsonpath.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Recorded responses, the way they come from the server.
 */
final class Fixtures {
    private Fixtures() {
    }

    static void collect(File file, List<File> out) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    collect(child, out);
                }
            }
        } else if (file.getName().endsWith(".json")) {
            out.add(file);
        }
    }

    /**
     * @param minify remove the formatting. The app asks the server for prettyPrint=false but most
     *               of the recorded files are pretty printed (two thirds of them is indentation).
     */
    static byte[] load(File file, boolean minify) throws IOException {
        byte[] bytes = stripPreamble(Files.readAllBytes(file.toPath()));

        return minify ? minify(bytes) : bytes;
    }

    /**
     * Some of the recorded files start with notes of the developer (request url, comments).
     * The server doesn't send those.
     */
    static byte[] stripPreamble(byte[] bytes) {
        int lineStart = 0;

        while (lineStart < bytes.length) {
            int i = lineStart;

            while (i < bytes.length && (bytes[i] == ' ' || bytes[i] == '\t')) {
                i++;
            }

            if (i < bytes.length && (bytes[i] == '{' || bytes[i] == '[')) {
                if (lineStart == 0) {
                    return bytes;
                }

                byte[] result = new byte[bytes.length - lineStart];
                System.arraycopy(bytes, lineStart, result, 0, result.length);
                return result;
            }

            while (i < bytes.length && bytes[i] != '\n') {
                i++;
            }

            lineStart = i + 1;
        }

        return bytes;
    }

    /**
     * Removes whitespace outside of the strings. The content isn't touched.
     */
    static byte[] minify(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length / 2);
        boolean inString = false;
        boolean escaped = false;

        for (byte b : bytes) {
            if (inString) {
                out.write(b);
                if (escaped) {
                    escaped = false;
                } else if (b == '\\') {
                    escaped = true;
                } else if (b == '"') {
                    inString = false;
                }
            } else if (b != ' ' && b != '\n' && b != '\r' && b != '\t') {
                inString = b == '"';
                out.write(b);
            }
        }

        return out.toByteArray();
    }
}
