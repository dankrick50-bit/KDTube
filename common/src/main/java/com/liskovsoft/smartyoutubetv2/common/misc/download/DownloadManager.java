package com.liskovsoft.smartyoutubetv2.common.misc.download;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.source.MediaSource;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Saves the videos to the device, one at a time.<br/>
 * The public methods should be called from the main thread, the listeners are called there too.
 */
public class DownloadManager {
    private static final String TAG = DownloadManager.class.getSimpleName();
    /**
     * Id of the section of the main screen (see BrowsePresenter).<br/>
     * Should differ from MediaGroup.TYPE_* and be below 100 (SidebarService.RESERVED_ID).<br/>
     * NOTE: the sections are compared by Video.hashCode(), that is |id - 31|: ids 40-62 are the same as 0-22 for it.
     */
    public static final int SECTION_ID = 70;
    private static final String INDEX_FILE_NAME = "downloads.json";
    /**
     * The source reports "please, reload the formats" sometimes (expired token etc)
     */
    private static final int MAX_ATTEMPTS = 2;
    @SuppressLint("StaticFieldLeak")
    private static DownloadManager sInstance;
    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final List<DownloadItem> mItems = new ArrayList<>();
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    private HandlerThread mThread;
    private Handler mWorkHandler;
    private DownloadItem mCurrent;
    private MediaItemFormatInfo mCurrentInfo;
    private MediaSourceDownloader mDownloader;
    private DownloadTarget mTarget;
    private int mAttempt;
    /**
     * Changes on every start/cancel: the reports of the old runs are ignored
     */
    private int mRunId;

    public interface Listener {
        /**
         * Something has changed: the state or the progress of the item, or the list itself
         */
        void onDownloadsChanged(@Nullable DownloadItem item);
    }

    private DownloadManager(Context context) {
        mContext = context.getApplicationContext();
        load();
    }

    public static DownloadManager instance(Context context) {
        if (sInstance == null) {
            sInstance = new DownloadManager(context);
        }

        return sInstance;
    }

    public void addListener(Listener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    /**
     * @param formatInfo the formats that are loaded already (for the player). Null: will be loaded.
     */
    public DownloadItem enqueue(Video video, DownloadOption option, @Nullable MediaItemFormatInfo formatInfo) {
        DownloadItem item = new DownloadItem();
        item.videoId = video.videoId;
        // NOTE: a video that is opened by a link has no title of its own, e.g.
        item.title = video.getTitleFull() != null ? video.getTitleFull() :
                formatInfo != null && formatInfo.getTitle() != null ? formatInfo.getTitle() : video.videoId;
        item.author = video.getAuthor() != null ? video.getAuthor() : formatInfo != null ? formatInfo.getAuthor() : null;
        item.cardImageUrl = video.getCardImageUrl() != null ? video.getCardImageUrl() :
                String.format("https://i.ytimg.com/vi/%s/hqdefault.jpg", video.videoId);
        item.quality = option.getQualityLabel();
        item.durationMs = video.getDurationMs();
        item.sizeBytes = option.sizeBytes;
        item.createdMs = System.currentTimeMillis();
        item.state = DownloadItem.STATE_QUEUED;
        item.option = option;

        mItems.add(0, item);
        notifyChanged(item);

        if (mCurrent == null) {
            startNext(item, formatInfo);
        }

        return item;
    }

    /**
     * Newest first
     */
    public List<DownloadItem> getItems() {
        return new ArrayList<>(mItems);
    }

    /**
     * Completed ones, newest first
     */
    public List<Video> getVideos() {
        List<Video> result = new ArrayList<>();
        boolean changed = false;

        for (DownloadItem item : new ArrayList<>(mItems)) {
            if (item.state != DownloadItem.STATE_COMPLETED) {
                continue;
            }

            // Removed with a file manager, e.g.
            if (!DownloadTarget.from(mContext, item.location).exists()) {
                mItems.remove(item);
                changed = true;
                continue;
            }

            result.add(item.toVideo());
        }

        if (changed) {
            save();
        }

        return result;
    }

    @Nullable
    public DownloadItem findActive(String videoId) {
        for (DownloadItem item : mItems) {
            if (item.isActive() && Helpers.equals(item.videoId, videoId)) {
                return item;
            }
        }

        return null;
    }

    @Nullable
    public DownloadItem findCompleted(String videoId) {
        for (DownloadItem item : mItems) {
            // NOTE: the file could be removed with a file manager
            if (item.state == DownloadItem.STATE_COMPLETED && Helpers.equals(item.videoId, videoId) &&
                    DownloadTarget.from(mContext, item.location).exists()) {
                return item;
            }
        }

        return null;
    }

    public boolean hasActive() {
        for (DownloadItem item : mItems) {
            if (item.isActive()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Stops the download (or removes it from the queue)
     */
    public void cancel(DownloadItem item) {
        if (!mItems.remove(item)) {
            return;
        }

        if (item == mCurrent) {
            stopCurrent(true);
            startNext(null, null);
        }

        notifyChanged(null);
    }

    /**
     * Removes the item together with its file
     */
    public void delete(DownloadItem item) {
        if (item.isActive()) {
            cancel(item);
            return;
        }

        if (!mItems.remove(item)) {
            return;
        }

        if (item.location != null) {
            DownloadTarget.from(mContext, item.location).delete();
        }

        save();
        notifyChanged(null);
    }

    public void deleteByVideoId(String videoId) {
        DownloadItem item = findCompleted(videoId);

        if (item != null) {
            delete(item);
        }
    }

    private void startNext(@Nullable DownloadItem preferred, @Nullable MediaItemFormatInfo formatInfo) {
        DownloadItem next = preferred;

        if (next == null) {
            // The oldest one of the queue
            for (int i = mItems.size() - 1; i >= 0; i--) {
                if (mItems.get(i).state == DownloadItem.STATE_QUEUED) {
                    next = mItems.get(i);
                    break;
                }
            }
        }

        if (next == null) {
            releaseThread();
            DownloadService.stop(mContext);
            return;
        }

        mCurrent = next;
        mCurrentInfo = formatInfo;
        mAttempt = 1;
        next.state = DownloadItem.STATE_RUNNING;
        next.percent = 0;
        notifyChanged(next);

        DownloadService.start(mContext);
        run(next, ++mRunId);
    }

    private void run(DownloadItem item, int runId) {
        if (mThread == null) {
            mThread = new HandlerThread("Downloads", Process.THREAD_PRIORITY_BACKGROUND);
            mThread.start();
            mWorkHandler = new Handler(mThread.getLooper());
        }

        MediaItemFormatInfo loadedInfo = mCurrentInfo;
        mCurrentInfo = null;
        Looper looper = mThread.getLooper();

        mWorkHandler.post(() -> {
            try {
                MediaItemFormatInfo formatInfo = loadedInfo;
                DownloadOption option = item.option;

                if (formatInfo == null) {
                    formatInfo = YouTubeServiceManager.instance().getMediaItemService().getFormatInfo(item.videoId);

                    if (formatInfo == null || formatInfo.isUnplayable()) {
                        throw new IOException(formatInfo != null && formatInfo.getPlayabilityReason() != null ?
                                formatInfo.getPlayabilityReason() : "Can't load the formats of the video");
                    }

                    option = DownloadOption.findSame(DownloadOption.from(formatInfo, item.option.audioLanguage), item.option);

                    if (option == null) {
                        throw new IOException("The selected quality isn't available anymore");
                    }
                }

                MediaSource source = createSource(formatInfo);
                DownloadOption finalOption = option;
                String name = item.title + (option.isAudioOnly() ? "" : " " + option.getQualityLabel()) + " [" + item.videoId + "]";
                DownloadTarget target = DownloadTarget.create(mContext, name, option.extension, option.mimeType);

                MediaSourceDownloader.WriterFactory writerFactory = () -> finalOption.isWebm ?
                        new WebmSampleWriter(target.openChannel(), null) :
                        new Mp4SampleWriter(target.createMuxer(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4));

                MediaSourceDownloader downloader = new MediaSourceDownloader(looper, source, option.videoId, option.audioId, option.audioLanguage,
                        writerFactory, new MediaSourceDownloader.Listener() {
                    @Override
                    public void onProgress(long positionUs, long durationUs, long bytesWritten) {
                        int percent = durationUs > 0 ? (int) Math.min(99, positionUs * 100 / durationUs) : 0;
                        mMainHandler.post(() -> handleProgress(item, runId, percent, bytesWritten));
                    }

                    @Override
                    public void onCompleted(long bytesWritten) {
                        target.commit();
                        long size = target.length();
                        mMainHandler.post(() -> handleCompleted(item, runId, target, size > 0 ? size : bytesWritten, finalOption));
                    }

                    @Override
                    public void onError(Throwable error) {
                        target.delete();
                        mMainHandler.post(() -> handleFailed(item, runId, error));
                    }
                });

                mMainHandler.post(() -> {
                    if (runId == mRunId) {
                        mDownloader = downloader;
                        mTarget = target;
                        downloader.start();
                    } else {
                        target.delete(); // cancelled in the meantime
                    }
                });
            } catch (Exception e) {
                mMainHandler.post(() -> handleFailed(item, runId, e));
            }
        });
    }

    private MediaSource createSource(MediaItemFormatInfo formatInfo) throws IOException {
        ExoMediaSourceFactory factory = new ExoMediaSourceFactory(mContext);

        if (formatInfo.containsDashFormats()) {
            return factory.fromDashFormatInfo(formatInfo);
        } else if (formatInfo.containsSabrFormats()) {
            return factory.fromSabrFormatInfo(formatInfo);
        }

        throw new IOException("This video has no formats that can be saved");
    }

    private void handleProgress(DownloadItem item, int runId, int percent, long bytesWritten) {
        if (runId != mRunId || item != mCurrent) {
            return;
        }

        if (item.percent != percent) {
            item.percent = percent;
            item.sizeBytes = Math.max(item.sizeBytes, bytesWritten);
            notifyChanged(item);
        }
    }

    private void handleCompleted(DownloadItem item, int runId, DownloadTarget target, long sizeBytes, DownloadOption option) {
        if (runId != mRunId || item != mCurrent) {
            target.delete();
            return;
        }

        Log.d(TAG, "Download completed: %s, %s bytes", item.title, sizeBytes);

        item.state = DownloadItem.STATE_COMPLETED;
        item.percent = 100;
        item.location = target.getLocation();
        item.sizeBytes = sizeBytes;
        item.quality = option.getQualityLabel();

        // The older copy of the same video isn't needed anymore
        for (DownloadItem other : new ArrayList<>(mItems)) {
            if (other != item && other.state == DownloadItem.STATE_COMPLETED && Helpers.equals(other.videoId, item.videoId)) {
                mItems.remove(other);
                DownloadTarget.from(mContext, other.location).delete();
            }
        }

        mCurrent = null;
        mDownloader = null;
        mTarget = null;
        save();
        notifyChanged(item);
        MessageHelpers.showLongMessage(mContext, mContext.getString(R.string.download_completed, item.title));
        startNext(null, null);
    }

    private void handleFailed(DownloadItem item, int runId, Throwable error) {
        if (runId != mRunId || item != mCurrent) {
            return;
        }

        Log.e(TAG, "Download failed (attempt %s): %s", mAttempt, error);

        mDownloader = null;
        mTarget = null;

        if (mAttempt < MAX_ATTEMPTS) {
            // Once again, with the fresh formats
            mAttempt++;
            item.percent = 0;
            notifyChanged(item);
            run(item, ++mRunId);
            return;
        }

        item.state = DownloadItem.STATE_FAILED;
        item.error = error.getMessage() != null ? error.getMessage() : error.toString();
        mCurrent = null;
        mItems.remove(item);
        notifyChanged(item);
        MessageHelpers.showLongMessage(mContext, mContext.getString(R.string.download_failed, item.error));
        startNext(null, null);
    }

    private void stopCurrent(boolean deleteFile) {
        mRunId++;

        if (mDownloader != null) {
            mDownloader.cancel();
            mDownloader = null;
        }

        DownloadTarget target = mTarget;
        mTarget = null;

        if (target != null && deleteFile && mWorkHandler != null) {
            // After the writer is closed (the same thread)
            mWorkHandler.post(target::delete);
        }

        mCurrent = null;
    }

    private void releaseThread() {
        if (mThread != null) {
            mThread.quitSafely();
            mThread = null;
            mWorkHandler = null;
        }
    }

    private void notifyChanged(@Nullable DownloadItem item) {
        for (Listener listener : mListeners) {
            listener.onDownloadsChanged(item);
        }
    }

    private File getIndexFile() {
        return new File(mContext.getFilesDir(), INDEX_FILE_NAME);
    }

    private void load() {
        File file = getIndexFile();

        if (!file.exists()) {
            return;
        }

        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int length = 0;

            for (int read; length < data.length && (read = input.read(data, length, data.length - length)) > 0; ) {
                length += read;
            }

            JSONArray items = new JSONArray(new String(data, 0, length, StandardCharsets.UTF_8));

            for (int i = 0; i < items.length(); i++) {
                JSONObject json = items.getJSONObject(i);
                DownloadItem item = new DownloadItem();
                item.videoId = json.optString("videoId", null);
                item.title = json.optString("title", null);
                item.author = json.optString("author", null);
                item.cardImageUrl = json.optString("cardImageUrl", null);
                item.quality = json.optString("quality", null);
                item.location = json.optString("location", null);
                item.sizeBytes = json.optLong("sizeBytes");
                item.durationMs = json.optLong("durationMs");
                item.createdMs = json.optLong("createdMs");
                item.state = DownloadItem.STATE_COMPLETED;

                if (item.location != null && item.videoId != null) {
                    mItems.add(item);
                }
            }
        } catch (IOException | JSONException | RuntimeException e) {
            Log.e(TAG, "Can't read the list of the downloads: %s", e.getMessage());
        }
    }

    private void save() {
        try {
            JSONArray items = new JSONArray();

            for (DownloadItem item : mItems) {
                if (item.state != DownloadItem.STATE_COMPLETED) {
                    continue;
                }

                JSONObject json = new JSONObject();
                json.put("videoId", item.videoId);
                json.put("title", item.title);
                json.put("author", item.author);
                json.put("cardImageUrl", item.cardImageUrl);
                json.put("quality", item.quality);
                json.put("location", item.location);
                json.put("sizeBytes", item.sizeBytes);
                json.put("durationMs", item.durationMs);
                json.put("createdMs", item.createdMs);
                items.put(json);
            }

            try (Writer writer = new OutputStreamWriter(new FileOutputStream(getIndexFile()), StandardCharsets.UTF_8)) {
                writer.write(items.toString());
            }
        } catch (IOException | JSONException | RuntimeException e) {
            Log.e(TAG, "Can't save the list of the downloads: %s", e.getMessage());
        }
    }
}
