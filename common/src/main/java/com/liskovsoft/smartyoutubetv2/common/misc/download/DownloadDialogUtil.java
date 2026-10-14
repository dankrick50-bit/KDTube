package com.liskovsoft.smartyoutubetv2.common.misc.download;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The dialog behind the download button of the player
 */
public class DownloadDialogUtil {
    /**
     * @param formatInfo formats of the video (null: not loaded yet)
     * @param audioLanguage language of the audio track that is playing now (the video may have several)
     */
    public static void showDownloadDialog(Context context, Video video, @Nullable MediaItemFormatInfo formatInfo, @Nullable String audioLanguage) {
        if (video == null || video.videoId == null) {
            return;
        }

        DownloadManager manager = DownloadManager.instance(context);
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        DownloadItem active = manager.findActive(video.videoId);
        DownloadItem completed = manager.findCompleted(video.videoId);
        // NOTE: a dialog with a single item isn't shown: the item is clicked right away.
        // That's why there's always something harmless to choose too.
        List<OptionItem> items = new ArrayList<>();

        if (active != null) {
            String progress = active.state == DownloadItem.STATE_RUNNING ?
                    context.getString(R.string.download_continue_running, active.percent) : context.getString(R.string.download_continue_queued);
            items.add(UiOptionItem.from(progress, optionItem -> dialog.closeDialog()));
            items.add(UiOptionItem.from(context.getString(R.string.download_cancel), optionItem -> {
                manager.cancel(active);
                MessageHelpers.showMessage(context, R.string.download_cancelled);
                dialog.closeDialog();
            }));
        } else if (video.localUri == null) { // otherwise the video is played from the file: the formats can't be loaded offline
            if (!DownloadOption.isSupported()) {
                MessageHelpers.showLongMessage(context, R.string.download_not_supported);
                return;
            }

            if (formatInfo == null) {
                MessageHelpers.showMessage(context, R.string.wait_data_loading);
                return;
            }

            List<DownloadOption> options = DownloadOption.from(formatInfo, audioLanguage);

            if (options.isEmpty()) {
                MessageHelpers.showLongMessage(context, R.string.download_no_formats);
                return;
            }

            if (DownloadTarget.requestPermissionIfNeeded(context)) {
                MessageHelpers.showLongMessage(context, R.string.download_grant_storage);
                return;
            }

            for (DownloadOption option : options) {
                items.add(UiOptionItem.from(getTitle(context, option), optionItem -> {
                    long freeBytes = DownloadTarget.getFreeBytes(context);

                    // NOTE: the size is approximate. Keep some space for the system.
                    if (freeBytes >= 0 && option.sizeBytes > 0 && option.sizeBytes + 50L * 1024 * 1024 > freeBytes) {
                        MessageHelpers.showLongMessage(context, context.getString(R.string.download_no_space, formatSize(freeBytes)));
                        return;
                    }

                    boolean queued = manager.hasActive();
                    manager.enqueue(video, option, queued ? null : formatInfo);
                    MessageHelpers.showMessage(context, queued ? R.string.download_queued : R.string.download_started);
                    dialog.closeDialog();
                }));
            }
        }

        if (active == null && completed != null) {
            String quality = completed.quality != null ? completed.quality : context.getString(R.string.download_audio_only);
            items.add(UiOptionItem.from(context.getString(R.string.download_delete_file, quality), optionItem -> {
                manager.delete(completed);
                MessageHelpers.showMessage(context, R.string.download_deleted);
                dialog.closeDialog();
            }));
        }

        if (items.isEmpty()) {
            return;
        }

        // Where it goes, and the way to change it
        if (active == null && video.localUri == null) {
            items.add(UiOptionItem.from(context.getString(R.string.download_folder_change, DownloadFolder.getLabel(context)),
                    optionItem -> {
                        dialog.closeDialog();
                        // Back here with the new folder in the line
                        DownloadFolder.showDialog(context, () -> showDownloadDialog(context, video, formatInfo, audioLanguage));
                    }));
        }

        if (items.size() == 1) {
            items.add(UiOptionItem.from(context.getString(R.string.cancel_dialog), optionItem -> dialog.closeDialog()));
        }

        dialog.appendStringsCategory(context.getString(R.string.download_video), items);
        dialog.showDialog(context.getString(R.string.download_video));
    }

    /**
     * The same dialog for a video known by its id only (the YouTube TV interface): its formats are loaded first.
     */
    public static void showDownloadDialog(Context context, String videoId) {
        if (videoId == null) {
            return;
        }

        MessageHelpers.showMessage(context, R.string.wait_data_loading);
        Handler handler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            MediaItemFormatInfo formatInfo = null;

            try {
                formatInfo = YouTubeServiceManager.instance().getMediaItemService().getFormatInfo(videoId);
            } catch (RuntimeException e) {
                // shown below
            }

            MediaItemFormatInfo info = formatInfo;
            handler.post(() -> {
                if (info == null || info.isUnplayable()) {
                    MessageHelpers.showLongMessage(context, R.string.download_no_formats);
                    return;
                }

                Video video = Video.from(videoId);
                video.title = info.getTitle();
                video.author = info.getAuthor();
                showDownloadDialog(context, video, info, null);
            });
        }, "kdtube-download-info").start();
    }

    private static String getTitle(Context context, DownloadOption option) {
        String name = option.isAudioOnly() ? context.getString(R.string.download_audio_only) : option.getQualityLabel();
        String result = String.format("%s  %s", name, option.extension.toUpperCase(Locale.US));

        if (option.sizeBytes > 0) {
            result += "  ~" + formatSize(option.sizeBytes);
        }

        return result;
    }

    public static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f GB", bytes / (1024d * 1024 * 1024));
        }

        return String.format(Locale.US, "%d MB", Math.max(1, Math.round(bytes / (1024d * 1024))));
    }
}
