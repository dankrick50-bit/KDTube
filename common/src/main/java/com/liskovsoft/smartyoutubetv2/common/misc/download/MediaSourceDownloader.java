package com.liskovsoft.smartyoutubetv2.common.misc.download;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.FormatHolder;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.source.MediaPeriod;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MediaSource.MediaPeriodId;
import com.google.android.exoplayer2.source.SampleStream;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.FixedTrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.google.android.exoplayer2.util.MimeTypes;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Reads the tracks of a {@link MediaSource} without a player and writes them into a single file.<br/>
 * <br/>
 * The source is the same one the player uses, so whatever the player can load (sabr, dash) can be saved,
 * with the same requests. The samples go from the loader straight to the writer: nothing is decoded and
 * no temporary files are needed.<br/>
 * <br/>
 * NOTE: all the work is done on the thread of the looper that is passed to the constructor
 * (the loaders of the source report to the thread they were started from).
 */
public class MediaSourceDownloader implements MediaSource.SourceInfoRefreshListener, MediaPeriod.Callback {
    private static final String TAG = MediaSourceDownloader.class.getSimpleName();
    /**
     * How much is loaded ahead of the part that is written already
     */
    private static final long MAX_LOAD_AHEAD_US = 10_000_000;
    private static final long MAX_LOAD_AHEAD_BYTES = 12 * 1024 * 1024;
    /**
     * The loaders report to the same thread. Don't keep it busy for long.
     */
    private static final int MAX_SAMPLES_PER_TURN = 300;
    private static final long IDLE_DELAY_MS = 15;
    private static final long PROGRESS_INTERVAL_MS = 1_000;
    /**
     * Nothing is loaded and nothing is written for this long: the source is stuck
     */
    private static final long STALL_TIMEOUT_MS = 90_000;

    public interface Listener {
        void onProgress(long positionUs, long durationUs, long bytesWritten);
        void onCompleted(long bytesWritten);
        void onError(Throwable error);
    }

    public interface WriterFactory {
        /**
         * Called once, when the first data is about to be written
         */
        SampleWriter create() throws IOException;
    }

    private final Handler mHandler;
    private final MediaSource mSource;
    private final String mVideoId;
    private final String mAudioId;
    private final String mAudioLanguage;
    private final WriterFactory mWriterFactory;
    private final Listener mListener;
    private final DefaultAllocator mAllocator = new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
    private final FormatHolder mFormatHolder = new FormatHolder();
    private final DecoderInputBuffer mBuffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    private final DecoderInputBuffer mFlagsOnlyBuffer = DecoderInputBuffer.newFlagsOnlyInstance();
    private final Runnable mDoWork = this::doWork;
    private MediaPeriod mPeriod;
    private SampleStream[] mStreams;
    private Format[] mFormats;
    private int[] mWriterTracks;
    private long[] mLastTimeUs;
    private boolean[] mEnded;
    private SampleWriter mWriter;
    private boolean mWriterStarted;
    private long mDurationUs = C.TIME_UNSET;
    private long mBytesWritten;
    private long mLastProgressMs;
    private long mLastActivityMs;
    private boolean mPrepared;
    private boolean mReleased;

    /**
     * @param videoId id of the video track to save (itag). Null: audio only
     * @param audioId id of the audio track to save (itag). Null: video only
     * @param audioLanguage in case there are several tracks with the same id
     */
    public MediaSourceDownloader(Looper looper, MediaSource source, @Nullable String videoId, @Nullable String audioId,
                                 @Nullable String audioLanguage, WriterFactory writerFactory, Listener listener) {
        mHandler = new Handler(looper);
        mSource = source;
        mVideoId = videoId;
        mAudioId = audioId;
        mAudioLanguage = audioLanguage;
        mWriterFactory = writerFactory;
        mListener = listener;
    }

    public void start() {
        mHandler.post(() -> {
            if (mReleased) {
                return;
            }

            try {
                mLastActivityMs = SystemClock.elapsedRealtime();
                mSource.prepareSource(this, null);
            } catch (RuntimeException e) {
                fail(e);
            }
        });
    }

    /**
     * Stops the work. The listener isn't called after that. The file should be removed by the caller.
     */
    public void cancel() {
        mHandler.post(this::release);
    }

    @Override
    public void onSourceInfoRefreshed(MediaSource source, Timeline timeline, @Nullable Object manifest) {
        if (mReleased || mPeriod != null) {
            return;
        }

        try {
            if (timeline.isEmpty()) {
                throw new IOException("Nothing to download: the source is empty");
            }

            Timeline.Period period = timeline.getPeriod(0, new Timeline.Period(), true);
            mDurationUs = period.durationUs;

            if (mDurationUs == C.TIME_UNSET || timeline.getWindow(0, new Timeline.Window()).isDynamic) {
                throw new IOException("Live streams can't be downloaded");
            }

            mPeriod = mSource.createPeriod(new MediaPeriodId(period.uid), mAllocator, 0);
            mPeriod.prepare(this, 0);
            schedule(IDLE_DELAY_MS);
        } catch (IOException | RuntimeException e) {
            fail(e);
        }
    }

    @Override
    public void onPrepared(MediaPeriod mediaPeriod) {
        if (mReleased || mPrepared) {
            return;
        }

        try {
            selectTracks();
            mPrepared = true;
            schedule(0);
        } catch (IOException | RuntimeException e) {
            fail(e);
        }
    }

    @Override
    public void onContinueLoadingRequested(MediaPeriod source) {
        schedule(0);
    }

    private void selectTracks() throws IOException {
        TrackGroupArray groups = mPeriod.getTrackGroups();
        TrackSelection video = mVideoId != null ? findTrack(groups, mVideoId, null, true) : null;
        TrackSelection audio = mAudioId != null ? findTrack(groups, mAudioId, mAudioLanguage, false) : null;

        if (audio == null && mAudioId != null) {
            audio = findTrack(groups, mAudioId, null, false);
        }

        if ((mVideoId != null && video == null) || (mAudioId != null && audio == null)) {
            throw new IOException("The selected format isn't available anymore: " + mVideoId + "+" + mAudioId);
        }

        int count = (video != null ? 1 : 0) + (audio != null ? 1 : 0);
        TrackSelection[] selections = new TrackSelection[count];
        int idx = 0;

        if (video != null) {
            selections[idx++] = video;
        }

        if (audio != null) {
            selections[idx] = audio;
        }

        for (TrackSelection selection : selections) {
            selection.enable();
        }

        mStreams = new SampleStream[count];
        mFormats = new Format[count];
        mWriterTracks = new int[count];
        mLastTimeUs = new long[count];
        mEnded = new boolean[count];

        mPeriod.selectTracks(selections, new boolean[count], mStreams, new boolean[count], 0);

        for (SampleStream stream : mStreams) {
            if (stream == null) {
                throw new IOException("Can't open the selected format: " + mVideoId + "+" + mAudioId);
            }
        }
    }

    private static TrackSelection findTrack(TrackGroupArray groups, String id, @Nullable String language, boolean isVideo) {
        for (int i = 0; i < groups.length; i++) {
            TrackGroup group = groups.get(i);

            for (int j = 0; j < group.length; j++) {
                Format format = group.getFormat(j);
                String mimeType = format.sampleMimeType != null ? format.sampleMimeType : format.containerMimeType;
                boolean typeMatched = isVideo ? (MimeTypes.isVideo(mimeType) || format.height > 0) : (MimeTypes.isAudio(mimeType) || format.height <= 0);

                if (typeMatched && Helpers.equals(format.id, id) && (language == null || Helpers.equals(format.language, language))) {
                    return new FixedTrackSelection(group, j);
                }
            }
        }

        return null;
    }

    private void schedule(long delayMs) {
        if (mReleased) {
            return;
        }

        mHandler.removeCallbacks(mDoWork);

        if (delayMs > 0) {
            mHandler.postDelayed(mDoWork, delayMs);
        } else {
            mHandler.post(mDoWork);
        }
    }

    private void doWork() {
        if (mReleased) {
            return;
        }

        try {
            if (!mPrepared) {
                mPeriod.maybeThrowPrepareError();
                checkStall();
                schedule(IDLE_DELAY_MS);
                return;
            }

            for (SampleStream stream : mStreams) {
                stream.maybeThrowError();
            }

            // NOTE: the player does the same on every turn. A discontinuity isn't expected here (no seeks).
            mPeriod.readDiscontinuity();

            boolean active = mWriterStarted ? writeSamples() : readFormats();

            if (isEnded()) {
                complete();
                return;
            }

            long readPositionUs = getReadPositionUs();

            mPeriod.discardBuffer(readPositionUs, false);

            long bufferedUs = mPeriod.getBufferedPositionUs();
            boolean loadMore = bufferedUs != C.TIME_END_OF_SOURCE &&
                    (bufferedUs - readPositionUs < MAX_LOAD_AHEAD_US || !mWriterStarted) &&
                    mAllocator.getTotalBytesAllocated() < MAX_LOAD_AHEAD_BYTES;

            if (loadMore && mPeriod.continueLoading(readPositionUs)) {
                active = true;
            }

            if (active) {
                mLastActivityMs = SystemClock.elapsedRealtime();
            } else {
                checkStall();
            }

            reportProgress(readPositionUs);

            schedule(active ? 0 : IDLE_DELAY_MS);
        } catch (IOException | RuntimeException e) {
            fail(e);
        }
    }

    /**
     * The writer can't be started before the formats of all the tracks are known
     */
    private boolean readFormats() throws IOException {
        boolean allKnown = true;
        boolean active = false;

        for (int i = 0; i < mStreams.length; i++) {
            if (mFormats[i] != null) {
                continue;
            }

            mFlagsOnlyBuffer.clear();
            int result = mStreams[i].readData(mFormatHolder, mFlagsOnlyBuffer, true);

            if (result == C.RESULT_FORMAT_READ) {
                mFormats[i] = mFormatHolder.format;
                active = true;
            } else {
                allKnown = false;
            }
        }

        if (allKnown) {
            startWriter();
        }

        return active;
    }

    private void startWriter() throws IOException {
        mWriter = mWriterFactory.create();

        for (int i = 0; i < mFormats.length; i++) {
            mWriterTracks[i] = mWriter.addTrack(mFormats[i]);
        }

        mWriter.start();
        mWriterStarted = true;
        Log.d(TAG, "Writing started: %s", describe(mFormats));
    }

    private boolean writeSamples() throws IOException {
        int count = 0;

        while (count < MAX_SAMPLES_PER_TURN) {
            // Keep the tracks close to each other in the file: take the one that is behind
            int next = -1;

            for (int i = 0; i < mStreams.length; i++) {
                if (!mEnded[i] && (next == -1 || mLastTimeUs[i] < mLastTimeUs[next])) {
                    next = i;
                }
            }

            if (next == -1) {
                break;
            }

            mBuffer.clear();
            int result = mStreams[next].readData(mFormatHolder, mBuffer, false);

            if (result == C.RESULT_FORMAT_READ) {
                // The same track (fixed selection). Nothing to do.
                count++;
                continue;
            }

            if (result != C.RESULT_BUFFER_READ) {
                break; // not loaded yet
            }

            count++;

            if (mBuffer.isEndOfStream()) {
                mEnded[next] = true;
                continue;
            }

            writeSample(next);
        }

        return count > 0;
    }

    private void writeSample(int index) throws IOException {
        mBuffer.flip();
        ByteBuffer data = mBuffer.data;
        long timeUs = Math.max(mBuffer.timeUs, 0);

        mLastTimeUs[index] = timeUs;

        if (data == null || data.remaining() == 0 || mBuffer.isDecodeOnly()) {
            return;
        }

        int size = data.remaining();
        mWriter.writeSample(mWriterTracks[index], data, timeUs, mBuffer.isKeyFrame());
        mBytesWritten += size;
    }

    private boolean isEnded() {
        if (!mWriterStarted) {
            return false;
        }

        for (boolean ended : mEnded) {
            if (!ended) {
                return false;
            }
        }

        return true;
    }

    private long getReadPositionUs() {
        long result = Long.MAX_VALUE;

        for (int i = 0; i < mStreams.length; i++) {
            if (!mEnded[i]) {
                result = Math.min(result, mLastTimeUs[i]);
            }
        }

        return result == Long.MAX_VALUE ? mDurationUs : result;
    }

    private void checkStall() throws IOException {
        if (SystemClock.elapsedRealtime() - mLastActivityMs > STALL_TIMEOUT_MS) {
            throw new IOException("The download is stuck: nothing received for " + STALL_TIMEOUT_MS / 1_000 + " seconds");
        }
    }

    private void reportProgress(long positionUs) {
        long nowMs = SystemClock.elapsedRealtime();

        if (nowMs - mLastProgressMs >= PROGRESS_INTERVAL_MS) {
            mLastProgressMs = nowMs;
            mListener.onProgress(Math.min(positionUs, mDurationUs), mDurationUs, mBytesWritten);
        }
    }

    private void complete() {
        try {
            // NOTE: this is where the index of the file is written
            mWriter.finish();
        } catch (IOException | RuntimeException e) {
            fail(e);
            return;
        }

        long bytesWritten = mBytesWritten;
        release();
        mListener.onCompleted(bytesWritten);
    }

    private void fail(Throwable error) {
        if (mReleased) {
            return;
        }

        Log.e(TAG, "Download error: %s", error.getMessage());
        release();
        mListener.onError(error);
    }

    private void release() {
        if (mReleased) {
            return;
        }

        mReleased = true;
        mHandler.removeCallbacks(mDoWork);

        if (mWriter != null) {
            mWriter.release();
            mWriter = null;
        }

        try {
            if (mPeriod != null) {
                mSource.releasePeriod(mPeriod);
                mPeriod = null;
            }

            mSource.releaseSource(this);
        } catch (RuntimeException e) {
            Log.e(TAG, "Source release error: %s", e.getMessage());
        }

        mAllocator.reset();
    }

    private static String describe(Format[] formats) {
        StringBuilder result = new StringBuilder();

        for (Format format : formats) {
            if (format == null) {
                continue;
            }

            if (result.length() > 0) {
                result.append(" + ");
            }

            result.append(format.sampleMimeType);

            if (format.height > 0) {
                result.append(' ').append(format.height).append('p');
            }
        }

        return result.toString();
    }
}
