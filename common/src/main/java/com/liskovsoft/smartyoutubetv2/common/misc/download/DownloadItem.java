package com.liskovsoft.smartyoutubetv2.common.misc.download;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

/**
 * A file that is saved (or is being saved)
 */
public class DownloadItem {
    public static final int STATE_QUEUED = 0;
    public static final int STATE_RUNNING = 1;
    public static final int STATE_COMPLETED = 2;
    public static final int STATE_FAILED = 3;
    public String videoId;
    public String title;
    public String author;
    public String cardImageUrl;
    public String quality;
    /**
     * Uri (content://) or absolute path
     */
    public String location;
    public long sizeBytes;
    public long durationMs;
    public long createdMs;
    public int state;
    public DownloadOption option;
    public int percent;
    public String error;

    public boolean isActive() {
        return state == STATE_QUEUED || state == STATE_RUNNING;
    }

    /**
     * E.g. Author • 720p • 29 MB
     */
    private String getInfo() {
        StringBuilder result = new StringBuilder();

        for (String part : new String[] {author, quality, sizeBytes > 0 ? DownloadDialogUtil.formatSize(sizeBytes) : null}) {
            if (part == null || part.isEmpty()) {
                continue;
            }

            if (result.length() > 0) {
                result.append(" • ");
            }

            result.append(part);
        }

        return result.toString();
    }

    /**
     * @return the video that is played from the file
     */
    public Video toVideo() {
        Video video = new Video();
        video.videoId = videoId;
        video.title = title;
        video.author = author;
        video.secondTitle = getInfo();
        video.cardImageUrl = cardImageUrl != null ? cardImageUrl : String.format("https://i.ytimg.com/vi/%s/hqdefault.jpg", videoId);
        video.localUri = DownloadTarget.toUri(location).toString();
        return video;
    }
}
