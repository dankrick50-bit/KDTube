package com.liskovsoft.smartyoutubetv2.common.exoplayer.selector;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.VideoCapabilities;
import android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint;
import android.media.MediaCodecList;
import android.os.Build;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.util.MimeTypes;
import com.liskovsoft.sharedutils.helpers.DeviceHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What this device really plays smoothly, which is more than "the decoder takes this size".<br/>
 * <br/>
 * <b>The frame rate.</b> A decoder that takes 3840 x 2160 may do it at 30 frames and not at 60. The size alone
 * is all the player asked so far. Since Android 10 the makers of the chip declare what it keeps up with
 * (performance points): a stream above that is not chosen.<br/>
 * <b>Experience.</b> Where nothing is declared, the playback itself tells: {@link FrameDropWatchdog} notes a
 * stream that drops frames, and it isn't chosen on this device for a while. One codec, one size and up, one
 * frame rate and up: VP9 2160p60 too heavy still leaves VP9 2160p30 and 1440p60.<br/>
 * <br/>
 * NOTE: 1080p and less are never ruled out: something has to play.
 */
public class VideoLimits {
    private static final String TAG = VideoLimits.class.getSimpleName();
    private static final String PREFS_NAME = "kdtube_video_limits";
    private static final String KEY_TOO_HEAVY = "too_heavy";
    /**
     * A device is busy now and then (an update in the background): what was too heavy is tried again later
     */
    private static final long FORGET_AFTER_MS = 14L * 24 * 60 * 60 * 1000;
    private static final int MIN_LIMITED_HEIGHT = 1080;
    @SuppressLint("StaticFieldLeak")
    private static VideoLimits sInstance;
    private final SharedPreferences mPrefs;
    /**
     * codec|height|fps -> since when
     */
    private final Map<String, Long> mTooHeavy = new HashMap<>();
    private final Map<String, Boolean> mDeclared = new HashMap<>();

    private VideoLimits(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }

    public static VideoLimits instance(Context context) {
        if (sInstance == null) {
            sInstance = new VideoLimits(context);
        }

        return sInstance;
    }

    /**
     * @return false if the stream is known to be more than this device plays smoothly
     */
    public synchronized boolean isPlayable(@Nullable Format format) {
        int height = getHeight(format);

        if (height <= MIN_LIMITED_HEIGHT) {
            return true;
        }

        String codec = getCodec(format.sampleMimeType);
        int fps = getFps(format);

        for (String key : mTooHeavy.keySet()) {
            String[] parts = key.split("\\|");

            if (parts.length == 3 && parts[0].equals(codec) && height >= parse(parts[1]) && fps >= parse(parts[2])) {
                return false;
            }
        }

        return isDeclaredPlayable(format.sampleMimeType, format.width, format.height, fps);
    }

    /**
     * The stream dropped frames on this device: the next choices leave it (and the heavier ones) out
     *
     * @return false if the stream is one that is never left out (1080p and less)
     */
    public synchronized boolean markTooHeavy(@Nullable Format format) {
        int height = getHeight(format);

        if (height <= MIN_LIMITED_HEIGHT) {
            return false;
        }

        String key = getCodec(format.sampleMimeType) + "|" + height + "|" + getFps(format);
        Log.d(TAG, "Too heavy for this device: %s", key);
        mTooHeavy.put(key, System.currentTimeMillis());
        save();
        return true;
    }

    /**
     * A hardware decoder of the kind takes this size (as reported, no assumptions: for choosing a default)
     */
    public static boolean isHardwareSizeSupported(String mimeType, int width, int height) {
        if (Build.VERSION.SDK_INT < 21) {
            return false;
        }

        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder() || !DeviceHelpers.isHardwareAccelerated(info.getName()) ||
                        (Build.VERSION.SDK_INT >= 29 && !info.isHardwareAccelerated())) {
                    continue;
                }

                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mimeType)) {
                        VideoCapabilities capabilities = info.getCapabilitiesForType(type).getVideoCapabilities();

                        if (capabilities != null && capabilities.isSizeSupported(width, height)) {
                            return true;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // the list of the codecs can't be read
        }

        return false;
    }

    /**
     * E.g. "2160p60"
     */
    public static String getLabel(@Nullable Format format) {
        int height = getHeight(format);
        int fps = getFps(format);
        return height + "p" + (fps > 30 ? String.valueOf(fps) : "");
    }

    /**
     * The shorter side: a picture on its side (Shorts) is the same work for the chip
     */
    private static int getHeight(@Nullable Format format) {
        if (format == null || !MimeTypes.isVideo(format.sampleMimeType) || format.width <= 0 || format.height <= 0) {
            return -1;
        }

        return Math.min(format.width, format.height);
    }

    private static int getFps(Format format) {
        return format.frameRate > 0 ? Math.round(format.frameRate) : 30;
    }

    private static String getCodec(String mimeType) {
        return mimeType != null ? mimeType.replace("video/", "") : "unknown";
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Android 10+: what the hardware decoders of the kind declare they keep up with. Nothing declared: yes.
     */
    private boolean isDeclaredPlayable(String mimeType, int width, int height, int fps) {
        if (Build.VERSION.SDK_INT < 29 || mimeType == null) {
            return true;
        }

        String key = mimeType + "|" + width + "|" + height + "|" + fps;
        Boolean result = mDeclared.get(key);

        if (result == null) {
            result = checkPerformancePoints(mimeType, width, height, fps);
            mDeclared.put(key, result);

            if (!result) {
                Log.d(TAG, "The decoders don't keep up with %s %sx%s@%s", mimeType, width, height, fps);
            }
        }

        return result;
    }

    /**
     * No only when the size is declared at a lower frame rate and not at this one: a real limit of the frame rate.
     * NOTE: a size that isn't declared at all says nothing (some makers list less than the chip does): the
     * playback decides then (FrameDropWatchdog). A capable device shouldn't lose its 4K to a short list.
     */
    @SuppressLint("NewApi")
    private static boolean checkPerformancePoints(String mimeType, int width, int height, int fps) {
        boolean isSizeDeclared = false;

        try {
            PerformancePoint wanted = new PerformancePoint(width, height, fps);
            PerformancePoint sizeOnly = new PerformancePoint(width, height, Math.min(fps, 24));

            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder() || !info.isHardwareAccelerated() || !DeviceHelpers.isHardwareAccelerated(info.getName())) {
                    continue;
                }

                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(mimeType)) {
                        continue;
                    }

                    VideoCapabilities capabilities = info.getCapabilitiesForType(type).getVideoCapabilities();
                    List<PerformancePoint> points = capabilities != null ? capabilities.getSupportedPerformancePoints() : null;

                    if (points == null || points.isEmpty()) {
                        // This decoder declares nothing: no reason to say no
                        return true;
                    }

                    for (PerformancePoint point : points) {
                        if (point.covers(wanted)) {
                            return true;
                        }

                        if (point.covers(sizeOnly)) {
                            isSizeDeclared = true;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // the list of the codecs can't be read
            return true;
        }

        return !isSizeDeclared;
    }

    private void load() {
        Set<String> stored = mPrefs.getStringSet(KEY_TOO_HEAVY, null);

        if (stored == null) {
            return;
        }

        long now = System.currentTimeMillis();

        for (String entry : stored) {
            int at = entry.lastIndexOf('@');

            if (at <= 0) {
                continue;
            }

            long since = parseLong(entry.substring(at + 1));

            if (now - since < FORGET_AFTER_MS) {
                mTooHeavy.put(entry.substring(0, at), since);
            }
        }
    }

    private void save() {
        Set<String> stored = new HashSet<>();

        for (Map.Entry<String, Long> entry : mTooHeavy.entrySet()) {
            stored.add(entry.getKey() + "@" + entry.getValue());
        }

        mPrefs.edit().putStringSet(KEY_TOO_HEAVY, stored).apply();
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
