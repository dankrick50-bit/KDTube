package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.misc.MemoryKeeper;

/**
 * Limits the amount of memory the player takes for buffering.<br/>
 * <br/>
 * The buffer lives on the java heap and the default control limits it by duration only:
 * it keeps loading until the min duration is reached, no matter how big the stream is.
 * 30 seconds of 1080p is ~20 MB, the same 30 seconds of 4K is 75+ MB.<br/>
 * <br/>
 * The limit isn't a constant: {@link MemoryKeeper} moves it with the state of the device.<br/>
 * <br/>
 * NOTE: switching the default control to the size priority isn't safe here. The back buffer
 * counts too and it's released by time only: the player could stop loading with nothing to play.
 * This one never stops loading while there's less than the min forward buffer ahead.
 */
public class MemoryCappedLoadControl implements LoadControl {
    private static final String TAG = MemoryCappedLoadControl.class.getSimpleName();
    private final LoadControl mControl;
    private final MemoryKeeper mKeeper;
    private long mMaxBytes = -1;
    private boolean mIsLimited;

    public MemoryCappedLoadControl(LoadControl control, MemoryKeeper keeper) {
        mControl = control;
        mKeeper = keeper;
    }

    @Override
    public boolean shouldContinueLoading(long bufferedDurationUs, float playbackSpeed) {
        // NOTE: the control keeps its own state. Should be asked in any case.
        boolean result = mControl.shouldContinueLoading(bufferedDurationUs, playbackSpeed);

        long maxBytes = mKeeper.getPlayerBufferBytes();

        if (maxBytes != mMaxBytes) {
            onLimitChanged(maxBytes);
        }

        boolean isLimited = result && bufferedDurationUs >= mKeeper.getMinForwardBufferUs() &&
                mControl.getAllocator().getTotalBytesAllocated() >= maxBytes;

        if (isLimited != mIsLimited) {
            mIsLimited = isLimited;
            if (isLimited) {
                Log.d(TAG, "Buffer is limited to %s MB. Buffered: %s seconds", maxBytes / 1024 / 1024, bufferedDurationUs / 1_000_000);
            }
        }

        return result && !isLimited;
    }

    private void onLimitChanged(long maxBytes) {
        boolean isReduced = mMaxBytes != -1 && maxBytes < mMaxBytes;
        mMaxBytes = maxBytes;

        if (isReduced) {
            Log.d(TAG, "The limit of the buffer is %s MB now", maxBytes / 1024 / 1024);
        }

        applyAllocatorTarget();
    }

    /**
     * The allocator keeps the pieces that became free for reuse, up to its target: the memory stays taken.
     * With the target at the limit, the pieces that aren't needed anymore are really given back.
     */
    private void applyAllocatorTarget() {
        Allocator allocator = mControl.getAllocator();

        if (mMaxBytes > 0 && allocator instanceof DefaultAllocator) {
            // NOTE: has an effect only if this is lower than the current one: the size of the pool of free pieces
            ((DefaultAllocator) allocator).setTargetBufferSize((int) Math.min(Integer.MAX_VALUE, mMaxBytes));
        }
    }

    @Override
    public void onPrepared() {
        mKeeper.setPlayerActive(true);
        mControl.onPrepared();
    }

    @Override
    public void onTracksSelected(Renderer[] renderers, TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        mControl.onTracksSelected(renderers, trackGroups, trackSelections);
        // The control has just set its own target
        applyAllocatorTarget();
    }

    @Override
    public void onStopped() {
        mKeeper.setPlayerActive(false);
        mControl.onStopped();
    }

    @Override
    public void onReleased() {
        mKeeper.setPlayerActive(false);
        mControl.onReleased();
    }

    @Override
    public Allocator getAllocator() {
        return mControl.getAllocator();
    }

    @Override
    public long getBackBufferDurationUs() {
        return Math.min(mControl.getBackBufferDurationUs(), mKeeper.getBackBufferUs());
    }

    @Override
    public boolean retainBackBufferFromKeyframe() {
        return mControl.retainBackBufferFromKeyframe();
    }

    @Override
    public boolean shouldStartPlayback(long bufferedDurationUs, float playbackSpeed, boolean rebuffering) {
        return mControl.shouldStartPlayback(bufferedDurationUs, playbackSpeed, rebuffering);
    }
}
