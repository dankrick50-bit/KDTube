package com.liskovsoft.smartyoutubetv2.common.misc.cast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sending side of the YouTube remote control service ("lounge"): tells a screen to play a video.<br/>
 * <br/>
 * The same service the phone app uses when it's linked with a TV code. The screen plays the video
 * with its own YouTube app: nothing is streamed through this device.<br/>
 * <br/>
 * NOTE: all the methods block. Don't call them from the main thread.
 */
public class LoungeSender {
    private static final String LOUNGE_URL = "https://www.youtube.com/api/lounge";
    private static final String SCREEN_URL = LOUNGE_URL + "/pairing/get_screen";
    private static final String TOKEN_URL = LOUNGE_URL + "/pairing/get_lounge_token_batch";
    private static final String BIND_URL = LOUNGE_URL + "/bc/bind?VER=8&CVER=1&auth_failure_option=send_error";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String TOKEN_HEADER = "X-YouTube-LoungeId-Token";
    private static final Pattern SESSION_ID = Pattern.compile("\\[\"c\",\"([^\"]+)\"");
    private static final Pattern G_SESSION_ID = Pattern.compile("\\[\"S\",\"([^\"]+)\"");

    /**
     * @param code the code that the screen shows ("Link with TV code"), with or without spaces
     * @return the screen, null if the code isn't known to the service (wrong or expired)
     */
    public static CastDevice findScreen(String code) throws IOException {
        String digits = code != null ? code.replaceAll("[^0-9a-zA-Z]", "") : "";

        if (digits.isEmpty()) {
            return null;
        }

        CastHttp.Response response = CastHttp.post(SCREEN_URL, CastHttp.form("pairing_code", digits), FORM, null);

        if (response.code == 404) {
            return null;
        }

        try {
            JSONObject screen = new JSONObject(response.body).getJSONObject("screen");
            return new CastDevice(screen.optString("name", null), screen.getString("screenId"), null);
        } catch (JSONException e) {
            if (response.code >= 400) {
                return null;
            }

            throw new IOException("Unexpected answer of the pairing service (" + response.code + ")", e);
        }
    }

    /**
     * Starts the video on the screen (replaces what is playing there)
     */
    public static void play(String screenId, String senderName, String videoId, int positionSec) throws IOException {
        String token = getToken(screenId);
        Map<String, String> headers = new HashMap<>();
        headers.put(TOKEN_HEADER, token);
        headers.put("Origin", "https://www.youtube.com");

        // 1) a session
        String connect = CastHttp.form(
                "app", "web",
                "mdx-version", "3",
                "name", senderName,
                "id", UUID.randomUUID().toString(),
                "device", "REMOTE_CONTROL",
                "capabilities", "que,dsdtr,atp,mus",
                "magnaKey", "cloudPairedDevice",
                "ui", "false",
                "theme", "cl");

        CastHttp.Response session = CastHttp.post(BIND_URL + "&RID=1", connect, FORM, headers);

        if (session.code == 401 || session.code == 403) {
            throw new IOException("The screen has to be linked again (the access has expired)");
        }

        Matcher sessionId = SESSION_ID.matcher(session.body);
        Matcher gSessionId = G_SESSION_ID.matcher(session.body);

        if (session.code != 200 || !sessionId.find() || !gSessionId.find()) {
            throw new IOException("Can't connect to the screen (" + session.code + ")");
        }

        // 2) the command
        String command = CastHttp.form(
                "count", "1",
                "ofs", "0",
                "req0__sc", "setPlaylist",
                "req0_videoId", videoId,
                "req0_videoIds", videoId,
                "req0_currentIndex", "0",
                "req0_currentTime", String.valueOf(Math.max(0, positionSec)),
                "req0_audioOnly", "false",
                "req0_listId", "");

        String url = BIND_URL + "&RID=2&SID=" + CastHttp.encode(sessionId.group(1)) + "&gsessionid=" + CastHttp.encode(gSessionId.group(1));
        CastHttp.Response result = CastHttp.post(url, command, FORM, headers);

        if (result.code != 200) {
            throw new IOException("The screen didn't accept the video (" + result.code + ")");
        }
    }

    /**
     * The token is short lived (hours) and is given to anybody who knows the id of the screen
     */
    private static String getToken(String screenId) throws IOException {
        CastHttp.Response response = CastHttp.post(TOKEN_URL, CastHttp.form("screen_ids", screenId), FORM, null);

        try {
            JSONArray screens = new JSONObject(response.body).getJSONArray("screens");

            if (screens.length() > 0) {
                return screens.getJSONObject(0).getString("loungeToken");
            }
        } catch (JSONException e) {
            // Below
        }

        throw new IOException("The screen isn't available anymore (" + response.code + ")");
    }
}
