package com.liskovsoft.smartyoutubetv2.tv.ui.webtv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SoundEnhanceProcessor;
import com.liskovsoft.smartyoutubetv2.common.misc.MemoryKeeper;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The same interface as the PC edition has: YouTube in the form it has on a TV, shown by the web
 * engine of the device, with the page scripts of KDTube (no adverts, sponsored parts skipped, the
 * best resolution for the screen, picture &amp; sound). The scripts are the very files of the PC edition
 * (desktop/app/inject, packed as assets).<br/>
 * <br/>
 * It's an option next to the interface of the app, not a replacement: it needs more memory (a whole
 * web engine) and a web engine that is up to date. Settings &gt; "YouTube TV interface".
 */
public class TvInterfaceActivity extends Activity {
    private static final String TAG = TvInterfaceActivity.class.getSimpleName();
    private static final String TV_URL = "https://www.youtube.com/tv";
    // What YouTube gives its TV interface to. The same as in the PC edition (desktop/app/main.js).
    private static final String USER_AGENT = "Mozilla/5.0 (PS4; Leanback Shell) Cobalt/26.lts.0-qa; compatible; KDTube/" + BuildConfig.VERSION_NAME;
    private static final String SPONSOR_API = "https://sponsor.ajay.app/api/skipSegments";
    private static final String[] SPONSOR_CATEGORIES = {"sponsor", "selfpromo", "interaction"};
    private static final String[] SCRIPTS = {"kdtube-android-bridge.js", "enhance.js", "tube.js"};
    /**
     * The page is written for the web engines of the last years
     */
    private static final int MIN_ENGINE_VERSION = 80;
    private static final Pattern ENGINE_VERSION = Pattern.compile("Chrome/(\\d+)");
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private WebView mWebView;
    private String mScript;
    private boolean mIsPlaying;

    public static void start(Context context) {
        Intent intent = new Intent(context, TvInterfaceActivity.class);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    public static boolean isSupported() {
        // The page scripts are given to the page with a method that came with Android 4.4;
        // the web engine can be updated on its own since Android 5
        return Build.VERSION.SDK_INT >= 21;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        try {
            mWebView = new WebView(this);
        } catch (RuntimeException e) { // Failed to load WebView provider: No WebView installed
            MessageHelpers.showLongMessage(this, R.string.tv_interface_no_engine);
            finish();
            return;
        }

        int engineVersion = getEngineVersion(mWebView.getSettings().getUserAgentString());

        if (!isSupported() || (engineVersion > 0 && engineVersion < MIN_ENGINE_VERSION)) {
            Log.e(TAG, "The web engine is too old: %s", engineVersion);
            MessageHelpers.showLongMessage(this, R.string.tv_interface_no_engine);
            mWebView.destroy();
            mWebView = null;
            finish();
            return;
        }

        mScript = readScripts();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(mWebView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideSystemBars();

        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(USER_AGENT);
        // One unit of the page = one pixel of the screen: the page offers the resolutions that fit its
        // size, and it would see a 1080p TV as 960 x 540 otherwise. The page scripts ask for a page as
        // wide as the screen has pixels (viewportWidth); these two make the engine honour that.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        mWebView.setBackgroundColor(Color.BLACK);
        mWebView.setFocusable(true);
        mWebView.setFocusableInTouchMode(true);
        mWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mWebView.setVerticalScrollBarEnabled(false);
        mWebView.setHorizontalScrollBarEnabled(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(mWebView, true);

        mWebView.addJavascriptInterface(new Bridge(), "KDTubeAndroid");
        mWebView.setWebViewClient(new Client());
        mWebView.setWebChromeClient(new ChromeClient());
        mWebView.requestFocus();
        mWebView.loadUrl(TV_URL);
    }

    private float getDensity() {
        DisplayMetrics metrics = new DisplayMetrics();
        // NOTE: of the display, not of the resources: the app scales those for its own interface
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        return metrics.density > 0 ? metrics.density : 1;
    }

    /**
     * The longer side: the screen is used in landscape
     */
    private int getScreenWidth() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        return Math.max(metrics.widthPixels, metrics.heightPixels);
    }

    private static int getEngineVersion(String userAgent) {
        Matcher matcher = ENGINE_VERSION.matcher(userAgent != null ? userAgent : "");
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private String readScripts() {
        StringBuilder result = new StringBuilder();

        for (String name : SCRIPTS) {
            try (InputStream input = getAssets().open(name)) {
                result.append(new String(readAll(input), "UTF-8")).append("\n;\n");
            } catch (IOException e) {
                Log.e(TAG, "Can't read the page script %s: %s", name, e.getMessage());
            }
        }

        return result.toString();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;

        while ((read = input.read(buffer)) > 0) {
            output.write(buffer, 0, read);
        }

        return output.toByteArray();
    }

    /**
     * The page scripts have to be in place before the scripts of the page run: they filter the answers
     * of the server. The big scripts of the page are fetched one by one, so "a new page starts" and
     * "the page asks for a file" are both early enough; the scripts themselves take care to run once.
     */
    private void injectScripts() {
        if (mWebView != null && mScript != null) {
            mWebView.evaluateJavascript(mScript, null);
        }
    }

    private String getPageSettings() {
        PlayerTweaksData tweaks = PlayerTweaksData.instance(this);
        JSONObject settings = new JSONObject();

        try {
            settings.put("adblock", true);
            settings.put("sponsorblock", true);
            settings.put("sponsorCategories", new JSONArray(SPONSOR_CATEGORIES));
            settings.put("bestQuality", true);
            // The options of "Picture & sound" of the player of the app
            int sharpen = tweaks.getSharpenLevel();
            settings.put("sharpen", sharpen == PlayerTweaksData.SHARPEN_STRONG ? "strong" : sharpen == PlayerTweaksData.SHARPEN_LIGHT ? "light" : "off");
            int levelling = tweaks.getSoundLevelling();
            settings.put("levelling", levelling == SoundEnhanceProcessor.LEVELLING_NIGHT ? "night" : levelling == SoundEnhanceProcessor.LEVELLING_ON ? "on" : "off");
            settings.put("dialogue", tweaks.isDialogueBoostEnabled());
            settings.put("wide", tweaks.isWideStereoEnabled());
            settings.put("displayScale", getDensity());
            settings.put("viewportWidth", getScreenWidth());
            // Protected video: "no", although Android has the module for it. A device that says yes is
            // given the protected copy of ordinary videos too, and that one is limited to 720p here
            // (seen on a Fire HD 10: the same song, 1080p unprotected, 720p protected).
            // The price: films that YouTube sells or rents don't play in this interface.
            settings.put("protection", false);
            // The kinds of video the chip of this device doesn't decode: the page shouldn't choose them
            settings.put("blockCodecs", getSoftwareOnlyCodecs());
        } catch (JSONException e) {
            // can't happen with these keys
        }

        return settings.toString();
    }

    /**
     * The web engine can decode AV1 and VP9 on the processor when the chip can't. That works, and it
     * costs: dropped frames at higher resolutions, heat, battery. YouTube has every video in several
     * kinds, so the ones without a hardware decoder are simply not offered to the page.
     */
    private static JSONArray getSoftwareOnlyCodecs() {
        JSONArray result = new JSONArray();
        String[][] kinds = {{"video/av01", "av01"}, {"video/x-vnd.on2.vp9", "vp09"}, {"video/x-vnd.on2.vp9", "vp9"}};
        boolean hasHardwareAvc = hasHardwareDecoder("video/avc");

        for (String[] kind : kinds) {
            // NOTE: never everything. Without a hardware decoder for H.264 the device is an odd one: let the engine decide.
            if (hasHardwareAvc && !hasHardwareDecoder(kind[0])) {
                result.put(kind[1]);
            }
        }

        return result;
    }

    private static boolean hasHardwareDecoder(String mimeType) {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }

                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mimeType) && isHardware(info)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            // the list of the codecs can't be read: nothing is blocked
            return true;
        }

        return false;
    }

    private static boolean isHardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) {
            return info.isHardwareAccelerated();
        }

        String name = info.getName().toLowerCase();
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.") && !name.contains(".sw.");
    }

    private void loadSegments(String videoId, int token) {
        new Thread(() -> {
            JSONArray result = new JSONArray();

            if (videoId.matches("[\\w-]{6,20}")) {
                HttpURLConnection connection = null;

                try {
                    String url = SPONSOR_API + "?videoID=" + URLEncoder.encode(videoId, "UTF-8") +
                            "&categories=" + URLEncoder.encode(new JSONArray(SPONSOR_CATEGORIES).toString(), "UTF-8");
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(8_000);
                    connection.setReadTimeout(8_000);

                    // 404: nothing is known about the video
                    if (connection.getResponseCode() == 200) {
                        JSONArray list = new JSONArray(new String(readAll(connection.getInputStream()), "UTF-8"));

                        for (int i = 0; i < list.length(); i++) {
                            JSONObject item = list.getJSONObject(i);
                            JSONArray segment = item.optJSONArray("segment");
                            String action = item.optString("actionType", "skip");

                            if (segment != null && segment.length() == 2 && !"mute".equals(action) && !"full".equals(action)) {
                                JSONObject entry = new JSONObject();
                                entry.put("start", segment.getDouble(0));
                                entry.put("end", segment.getDouble(1));
                                entry.put("label", item.optString("category", "segment").replace("selfpromo", "self promotion")
                                        .replace("interaction", "like / subscribe reminder"));
                                result.put(entry);
                            }
                        }
                    }
                } catch (IOException | JSONException e) {
                    // no list: nothing is skipped
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }

            String script = "window.__kdtubeSegments && window.__kdtubeSegments(" + token + "," + result + ")";
            mHandler.post(() -> {
                if (mWebView != null) {
                    mWebView.evaluateJavascript(script, null);
                }
            });
        }, "kdtube-segments").start();
    }

    private void sendKey(int keyCode) {
        if (mWebView != null) {
            mWebView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            mWebView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        // The "back" of the page is the Esc key (it closes its dialogs, leaves a video, and asks before exit)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                sendKey(KeyEvent.KEYCODE_ESCAPE);
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                showMenu();
            }
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    private void showMenu() {
        String[] items = {getString(R.string.tv_interface_switch_back), getString(R.string.tv_interface_reload), getString(R.string.tv_interface_close)};

        new AlertDialog.Builder(this)
                .setTitle(R.string.tv_interface)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        MainUIData.instance(this).setTvInterfaceOnStartEnabled(false);
                        startMainInterface();
                    } else if (which == 1 && mWebView != null) {
                        mWebView.reload();
                    } else if (which == 2) {
                        finish();
                    }
                })
                .show();
    }

    private void startMainInterface() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());

        if (intent == null) {
            intent = getPackageManager().getLeanbackLaunchIntentForPackage(getPackageName());
        }

        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        }

        finish();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            hideSystemBars();
        }
    }

    private void setPlaying(boolean isPlaying) {
        mIsPlaying = isPlaying;
        // The screen doesn't go dark in the middle of a film, and the app isn't taken for idle
        if (isPlaying) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        MemoryKeeper.instance(this).setPlayerActive(isPlaying);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mWebView != null) {
            mWebView.onResume();
            mWebView.requestFocus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mWebView != null) {
            // Nothing keeps playing behind the back of the user
            mWebView.evaluateJavascript("(function(){var v=document.querySelector('video');if(v&&!v.paused)v.pause();})()", null);
            mWebView.onPause();
        }

        setPlaying(false);
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);

        if (mWebView != null) {
            ViewGroup parent = (ViewGroup) mWebView.getParent();
            if (parent != null) {
                parent.removeView(mWebView);
            }
            mWebView.removeJavascriptInterface("KDTubeAndroid");
            mWebView.destroy();
            mWebView = null;
        }

        super.onDestroy();
    }

    /**
     * What the page scripts may ask the app for. Called on a thread of the web engine.
     */
    private class Bridge {
        @JavascriptInterface
        public String settings() {
            return getPageSettings();
        }

        @JavascriptInterface
        public void segments(String videoId, int token) {
            loadSegments(videoId != null ? videoId : "", token);
        }

        @JavascriptInterface
        public void playing(boolean isPlaying) {
            mHandler.post(() -> {
                if (isPlaying != mIsPlaying) {
                    setPlaying(isPlaying);
                }
            });
        }

        @JavascriptInterface
        public void back() {
            mHandler.post(() -> sendKey(KeyEvent.KEYCODE_ESCAPE));
        }

        @JavascriptInterface
        public void log(String line) {
            Log.d(TAG, "page: %s", line);
        }
    }

    private class Client extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            injectScripts();
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            injectScripts();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String host = request.getUrl().getHost();
            // The screen stays with YouTube (and Google, for signing in)
            return host == null || !(host.endsWith("youtube.com") || host.endsWith("google.com") || host.endsWith("youtu.be"));
        }
    }

    private class ChromeClient extends WebChromeClient {
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            // Playing protected video: yes. The microphone and the camera: no.
            for (String resource : request.getResources()) {
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(resource)) {
                    request.grant(new String[] {PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                    return;
                }
            }

            request.deny();
        }

        @Override
        public void onCloseWindow(WebView window) {
            // "Exit" of the page
            finish();
        }
    }
}
