package com.liskovsoft.smartyoutubetv2.common.misc.download;

import android.os.Build;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.Helpers;

import java.util.ArrayList;
import java.util.List;

/**
 * What can be saved: a video track + an audio track (or the audio alone) that fit into one container.
 */
public class DownloadOption {
    /**
     * H.264 has frames that are stored out of order. The mp4 writer of Android can do that since 7.1
     */
    private static final int MIN_SDK_MP4_VIDEO = 25;
    /**
     * The mp4 writer of Android (audio only). Webm has a writer of its own (see WebmSampleWriter).
     */
    private static final int MIN_SDK = 21;
    private static final int MAX_FPS = 60;
    public final String videoId;
    public final String audioId;
    public final String audioLanguage;
    public final int height;
    public final int fps;
    public final String extension;
    public final String mimeType;
    public final boolean isWebm;
    /**
     * Approximate, -1 if unknown
     */
    public final long sizeBytes;

    private DownloadOption(@Nullable MediaFormat video, MediaFormat audio, boolean isWebm, long durationMs) {
        videoId = video != null ? video.getITag() : null;
        audioId = audio.getITag();
        audioLanguage = audio.getLanguage();
        height = video != null ? video.getHeight() : 0;
        fps = video != null ? Math.round(Helpers.parseFloat(video.getFps(), 0)) : 0;
        this.isWebm = isWebm;
        extension = isWebm ? "webm" : video != null ? "mp4" : "m4a";
        mimeType = video != null ? (isWebm ? "video/webm" : "video/mp4") : (isWebm ? "audio/webm" : "audio/mp4");

        long videoSize = video != null ? getSize(video, durationMs) : 0;
        long audioSize = getSize(audio, durationMs);
        sizeBytes = videoSize >= 0 && audioSize >= 0 ? videoSize + audioSize : -1;
    }

    public boolean isAudioOnly() {
        return videoId == null;
    }

    /**
     * E.g. 1080p60
     */
    public String getQualityLabel() {
        if (isAudioOnly()) {
            return null;
        }

        return height + "p" + (fps > 30 ? String.valueOf(fps) : "");
    }

    /**
     * @return true if this device is able to write the files
     */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= MIN_SDK;
    }

    /**
     * @param preferredLanguage audio language (when the video has several), may be null
     * @return best first, the audio only one is the last. Empty: nothing to save (e.g. live stream).
     */
    public static List<DownloadOption> from(MediaItemFormatInfo formatInfo, @Nullable String preferredLanguage) {
        List<DownloadOption> result = new ArrayList<>();

        if (formatInfo == null || formatInfo.isLive() || formatInfo.getAdaptiveFormats() == null || !isSupported()) {
            return result;
        }

        long durationMs = Helpers.parseLong(formatInfo.getLengthSeconds()) * 1_000;
        boolean mp4VideoSupported = Build.VERSION.SDK_INT >= MIN_SDK_MP4_VIDEO;

        List<MediaFormat> formats = formatInfo.getAdaptiveFormats();
        MediaFormat aac = findAudio(formats, "mp4a", preferredLanguage);
        MediaFormat opus = findAudio(formats, "opus", preferredLanguage);

        List<Integer> heights = new ArrayList<>();

        for (MediaFormat format : formats) {
            if (isVideo(format) && format.getHeight() > 0 && !heights.contains(format.getHeight())) {
                heights.add(format.getHeight());
            }
        }

        java.util.Collections.sort(heights, java.util.Collections.reverseOrder());

        for (int height : heights) {
            // The one that plays everywhere goes first
            MediaFormat avc = aac != null && mp4VideoSupported ? findVideo(formats, height, "avc1") : null;
            MediaFormat vp9 = opus != null ? findVideo(formats, height, "vp9", "vp09.00") : null;

            if (avc != null) {
                result.add(new DownloadOption(avc, aac, false, durationMs));
            } else if (vp9 != null) {
                result.add(new DownloadOption(vp9, opus, true, durationMs));
            }
        }

        if (aac != null) {
            result.add(new DownloadOption(null, aac, false, durationMs));
        } else if (opus != null) {
            result.add(new DownloadOption(null, opus, true, durationMs));
        }

        return result;
    }

    /**
     * @return the same option in the fresh list of the formats (the old one has expired, e.g.)
     */
    public static DownloadOption findSame(List<DownloadOption> options, DownloadOption origin) {
        for (DownloadOption option : options) {
            if (Helpers.equals(option.videoId, origin.videoId) && Helpers.equals(option.audioId, origin.audioId)) {
                return option;
            }
        }

        // Another codec of the same quality, e.g.
        for (DownloadOption option : options) {
            if (option.height == origin.height && option.isAudioOnly() == origin.isAudioOnly()) {
                return option;
            }
        }

        return null;
    }

    private static boolean isVideo(MediaFormat format) {
        return format.getMimeType() != null && format.getMimeType().startsWith("video/") && isLoadable(format);
    }

    private static boolean isAudio(MediaFormat format) {
        return format.getMimeType() != null && format.getMimeType().startsWith("audio/") && isLoadable(format);
    }

    private static boolean isLoadable(MediaFormat format) {
        // OTF: the stream is being processed by the server yet (no index)
        return !format.isOtf() && format.getFormatType() != MediaFormat.FORMAT_TYPE_REGULAR;
    }

    private static MediaFormat findVideo(List<MediaFormat> formats, int height, String... codecs) {
        MediaFormat result = null;

        for (MediaFormat format : formats) {
            if (!isVideo(format) || format.getHeight() != height || !Helpers.containsAny(format.getMimeType(), codecs)) {
                continue;
            }

            if (Helpers.parseFloat(format.getFps(), 0) > MAX_FPS + 1) {
                continue;
            }

            // 60 fps when there's a choice, then the bigger bitrate
            if (result == null || compare(format, result) > 0) {
                result = format;
            }
        }

        return result;
    }

    private static int compare(MediaFormat left, MediaFormat right) {
        int fps = Float.compare(Helpers.parseFloat(left.getFps(), 0), Helpers.parseFloat(right.getFps(), 0));

        return fps != 0 ? fps : Integer.compare(Helpers.parseInt(left.getBitrate(), 0), Helpers.parseInt(right.getBitrate(), 0));
    }

    private static MediaFormat findAudio(List<MediaFormat> formats, String codec, @Nullable String preferredLanguage) {
        MediaFormat result = null;
        boolean resultMatched = false;

        for (MediaFormat format : formats) {
            // DRC: the version with the compressed dynamic range. The original is better.
            if (!isAudio(format) || format.isDrc() || !Helpers.contains(format.getMimeType(), codec)) {
                continue;
            }

            boolean matched = preferredLanguage == null || format.getLanguage() == null || Helpers.equals(format.getLanguage(), preferredLanguage);

            if (result == null || (matched && !resultMatched) ||
                    (matched == resultMatched && Helpers.parseInt(format.getBitrate(), 0) > Helpers.parseInt(result.getBitrate(), 0))) {
                result = format;
                resultMatched = matched;
            }
        }

        return result;
    }

    private static long getSize(MediaFormat format, long durationMs) {
        long clen = Helpers.parseLong(format.getClen());

        if (clen > 0) {
            return clen;
        }

        int bitrate = Helpers.parseInt(format.getBitrate(), 0);

        return bitrate > 0 && durationMs > 0 ? (long) bitrate / 8 * durationMs / 1_000 : -1;
    }
}
