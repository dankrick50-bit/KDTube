package com.liskovsoft.smartyoutubetv2.tv.ui.webtv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Point;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.VideoCapabilities;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
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
import android.widget.TextView;

import com.liskovsoft.sharedutils.helpers.DeviceHelpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.autoframerate.internal.DisplayHolder.Mode;
import com.liskovsoft.smartyoutubetv2.common.autoframerate.internal.UhdHelper;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SoundEnhanceProcessor;
import com.liskovsoft.smartyoutubetv2.common.misc.MemoryKeeper;
import com.liskovsoft.smartyoutubetv2.common.misc.download.DownloadDialogUtil;
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
    private static final Pattern VIDEO_ID = Pattern.compile("[?&]v=([\\w-]{6,20})");
    /**
     * Back held this long opens the menu (the way back to the interface of the app)
     */
    private static final long BACK_HOLD_MS = 700;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private WebView mWebView;
    private String mScript;
    private boolean mIsPlaying;
    private boolean mIsBackHeld;
    private AlertDialog mMenu;

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
        addMenuButton(root);
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

    /**
     * A screen to touch has no remote to hold Back on: a small button in the corner opens the menu
     */
    private void addMenuButton(FrameLayout root) {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            return;
        }

        TextView button = new TextView(this);
        button.setText("⋮"); // vertical ellipsis
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundColor(0x66000000);
        button.setContentDescription(getString(R.string.tv_interface_menu_title));
        // Not in the way of the remote: the focus stays with the page
        button.setFocusable(false);
        button.setOnClickListener(view -> showMenu());

        int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40, getResources().getDisplayMetrics());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        root.addView(button, params);
    }

    private DisplayMetrics getRealMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        // NOTE: of the display, not of the resources: the app scales those for its own interface
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        return metrics;
    }

    /**
     * The biggest picture the screen can show, in its own pixels, landscape.<br/>
     * NOTE: not the size of the interface. TV boxes (Google TV, Fire TV) draw their interface at 1920 x 1080
     * on a 4K screen, and a page that measures that is never offered more than 1080p. The modes of the
     * display say what the screen really is (also on Fire OS, see UhdHelper).
     */
    private Point getDisplayPixels() {
        DisplayMetrics metrics = getRealMetrics();
        int width = Math.max(metrics.widthPixels, metrics.heightPixels);
        int height = Math.min(metrics.widthPixels, metrics.heightPixels);

        try {
            Mode[] modes = new UhdHelper(this).getSupportedModes();

            if (modes != null) {
                for (Mode mode : modes) {
                    if (mode == null) {
                        continue;
                    }

                    int modeWidth = Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight());
                    int modeHeight = Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight());

                    if ((long) modeWidth * modeHeight > (long) width * height) {
                        width = modeWidth;
                        height = modeHeight;
                    }
                }
            }
        } catch (RuntimeException e) {
            // the modes can't be read: the size of the interface
        }

        return new Point(width, height);
    }

    /**
     * The page scripts make the page see a screen of {@code screenWidth} pixels: what the page reads as the
     * size of the screen times this. The engine gives that size in its own units (pixels / density).
     */
    private float getDisplayScale(int screenWidth) {
        DisplayMetrics metrics = getRealMetrics();
        float density = metrics.density > 0 ? metrics.density : 1;
        float units = Math.max(metrics.widthPixels, metrics.heightPixels) / density;
        return units > 0 ? screenWidth / units : density;
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
            int bass = tweaks.getBassBoost();
            settings.put("bass", bass == SoundEnhanceProcessor.BASS_STRONG ? "strong" : bass == SoundEnhanceProcessor.BASS_LIGHT ? "light" : "off");
            // The page offers the resolutions that fit the screen: it's told the real one (4K, 8K), not
            // the 1080p the interface of a TV box is drawn in
            Point display = getDisplayPixels();
            settings.put("displayScale", getDisplayScale(display.x));
            settings.put("viewportWidth", display.x);
            // And no more than the chip decodes, per kind of video
            settings.put("codecLimits", getHardwareCodecLimits());
            settings.put("touchScroll", true);
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

    /**
     * The biggest picture the chip decodes, per kind of video: {"vp09": [3840, 2160], ...}. The page asks
     * "can you play VP9 at 3840 x 2160?" and the web engine looks at the codec only: 8K would be accepted on
     * a chip that stops at 4K, and 4K on one that stops at 1080p. Kinds without a hardware decoder are left
     * out (see getSoftwareOnlyCodecs).
     */
    private static JSONObject getHardwareCodecLimits() {
        JSONObject result = new JSONObject();
        String[][] kinds = {{"video/x-vnd.on2.vp9", "vp09", "vp9"}, {"video/av01", "av01"}, {"video/avc", "avc1"}};

        for (String[] kind : kinds) {
            int[] size = getHardwareMaxSize(kind[0]);

            if (size == null) {
                continue;
            }

            for (int i = 1; i < kind.length; i++) {
                try {
                    result.put(kind[i], new JSONArray().put(size[0]).put(size[1]));
                } catch (JSONException e) {
                    // can't happen with these keys
                }
            }
        }

        return result;
    }

    private static final int[][] VIDEO_SIZES = {{7680, 4320}, {5120, 2880}, {3840, 2160}, {2560, 1440}, {1920, 1080}, {1280, 720}};

    /**
     * @return the largest of the usual sizes (8K .. 720p) a hardware decoder of the kind takes, null if unknown
     */
    private static int[] getHardwareMaxSize(String mimeType) {
        int[] best = null;

        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder() || !isHardware(info) || !supportsType(info, mimeType)) {
                    continue;
                }

                VideoCapabilities capabilities = info.getCapabilitiesForType(mimeType).getVideoCapabilities();

                if (capabilities == null) {
                    continue;
                }

                for (int[] size : VIDEO_SIZES) {
                    if (isKnownSize(mimeType, size) && capabilities.isSizeSupported(size[0], size[1])) {
                        if (best == null || size[1] > best[1]) {
                            best = size;
                        }
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            // the list of the codecs can't be read: no limit
            return null;
        }

        return best;
    }

    private static boolean supportsType(MediaCodecInfo info, String mimeType) {
        for (String type : info.getSupportedTypes()) {
            if (type.equalsIgnoreCase(mimeType)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Some TV sticks report the size of the screen for VP9, not the one of the decoder (DeviceHelpers knows them)
     */
    private static boolean isKnownSize(String mimeType, int[] size) {
        return !"video/x-vnd.on2.vp9".equals(mimeType) || DeviceHelpers.isVP9ResolutionSupported(size[1]);
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

        // The "back" of the page is the Esc key (it closes its dialogs, leaves a video, and asks before exit).
        // Held down, it opens the menu of KDTube: most remotes (Google TV, ONN) have no menu key.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    mIsBackHeld = false;
                } else if (!mIsBackHeld && event.getEventTime() - event.getDownTime() >= BACK_HOLD_MS) {
                    mIsBackHeld = true;
                    showMenu();
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (!mIsBackHeld && !event.isCanceled()) {
                    sendKey(KeyEvent.KEYCODE_ESCAPE);
                }
                mIsBackHeld = false;
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
        if (mMenu != null && mMenu.isShowing()) {
            return;
        }

        // A video is open: it can be downloaded (the same dialog as in the player of the app)
        String videoId = getCurrentVideoId();
        String[] items = videoId != null ?
                new String[] {getString(R.string.download_video), getString(R.string.tv_interface_switch_back), getString(R.string.tv_interface_reload), getString(R.string.tv_interface_close)} :
                new String[] {getString(R.string.tv_interface_switch_back), getString(R.string.tv_interface_reload), getString(R.string.tv_interface_close)};
        int offset = videoId != null ? 1 : 0;

        mMenu = new AlertDialog.Builder(this)
                .setTitle(R.string.tv_interface_menu_title)
                .setItems(items, (dialog, choice) -> {
                    if (videoId != null && choice == 0) {
                        DownloadDialogUtil.showDownloadDialog(this, videoId);
                        return;
                    }

                    int which = choice - offset;

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

    /**
     * The video the page plays or shows: #/watch?v=&lt;id&gt;
     */
    private String getCurrentVideoId() {
        String url = mWebView != null ? mWebView.getUrl() : null;
        Matcher matcher = VIDEO_ID.matcher(url != null ? url : "");
        return matcher.find() ? matcher.group(1) : null;
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

        if (mMenu != null) {
            mMenu.dismiss();
            mMenu = null;
        }

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

        /**
         * An arrow key of the remote (touch scrolling of the page scripts)
         */
        @JavascriptInterface
        public void key(String name) {
            int keyCode = "Up".equals(name) ? KeyEvent.KEYCODE_DPAD_UP : "Down".equals(name) ? KeyEvent.KEYCODE_DPAD_DOWN :
                    "Left".equals(name) ? KeyEvent.KEYCODE_DPAD_LEFT : "Right".equals(name) ? KeyEvent.KEYCODE_DPAD_RIGHT : -1;

            if (keyCode != -1) {
                mHandler.post(() -> sendKey(keyCode));
            }
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
            // "Exit" of the page: the choice of where to go (the interface of the app is one of them).
            // Leaving outright took the app with it when this interface was the start screen.
            showMenu();
        }
    }
}
