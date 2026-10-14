package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.VideoLimits;

import java.util.ArrayDeque;

/**
 * A stream that the device can't keep up with drops frames: the picture judders while the quality looks
 * fine on paper. The decoder counts those frames. When it's more than a few per cent, the stream is noted
 * as too heavy for this device ({@link VideoLimits}) and the video goes on at the same spot with the next
 * lighter one: the same size at a lower frame rate, or the next size. Next time it's chosen right away.<br/>
 * <br/>
 * Only above 1080p, at normal speed, and not in the first seconds after a start, a seek or a switch (some
 * frames are always dropped there).
 */
public class FrameDropWatchdog extends BasePlayerController {
    private static final String TAG = FrameDropWatchdog.class.getSimpleName();
    private static final long CHECK_MS = 2_000;
    private static final long WINDOW_MS = 10_000;
    private static final long SETTLE_MS = 6_000;
    private static final int MIN_DROPPED_FRAMES = 24;
    private static final int MAX_DROPPED_PERCENT = 5;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mCheck = this::check;
    /**
     * {time, dropped, rendered}
     */
    private final ArrayDeque<long[]> mSamples = new ArrayDeque<>();
    private Format mFormat;
    private long mSettleUntilMs;
    private boolean mIsRunning;

    @Override
    public void onPlay() {
        settle();
        start();
    }

    @Override
    public void onPause() {
        stop();
    }

    @Override
    public void onPlayEnd() {
        stop();
    }

    @Override
    public void onEngineReleased() {
        stop();
        mFormat = null;
    }

    @Override
    public void onViewPaused() {
        stop();
    }

    @Override
    public void onSeekEnd() {
        settle();
    }

    @Override
    public void onBuffering() {
        settle();
    }

    @Override
    public void onSpeedChanged(float speed) {
        settle();
    }

    @Override
    public void onTrackChanged(FormatItem track) {
        settle();
    }

    private void start() {
        if (!mIsRunning) {
            mIsRunning = true;
            mHandler.postDelayed(mCheck, CHECK_MS);
        }
    }

    private void stop() {
        mIsRunning = false;
        mHandler.removeCallbacks(mCheck);
        mSamples.clear();
    }

    private void settle() {
        mSettleUntilMs = SystemClock.elapsedRealtime() + SETTLE_MS;
        mSamples.clear();
    }

    private void check() {
        if (!mIsRunning) {
            return;
        }

        mHandler.postDelayed(mCheck, CHECK_MS);

        PlayerManager player = getPlayer();

        if (player == null || !player.isPlaying() || Math.abs(player.getSpeed() - 1f) > 0.01f) {
            mSamples.clear();
            return;
        }

        DecoderCounters counters = player.getVideoDecoderCounters();
        Format format = player.getPlayingVideoFormat();

        if (counters == null || format == null) {
            mSamples.clear();
            return;
        }

        if (format != mFormat) {
            mFormat = format;
            settle();
        }

        long now = SystemClock.elapsedRealtime();

        if (now < mSettleUntilMs) {
            return;
        }

        counters.ensureUpdated();
        long[] sample = {now, counters.droppedBufferCount, counters.renderedOutputBufferCount};
        long[] last = mSamples.peekLast();

        // A new decoder starts its counters at zero
        if (last != null && (sample[1] < last[1] || sample[2] < last[2])) {
            mSamples.clear();
        }

        mSamples.addLast(sample);

        while (mSamples.size() > 2 && now - mSamples.peekFirst()[0] > WINDOW_MS) {
            mSamples.removeFirst();
        }

        long[] first = mSamples.peekFirst();

        if (first == null || now - first[0] < WINDOW_MS - CHECK_MS) {
            return; // not a whole window yet
        }

        long dropped = sample[1] - first[1];
        long rendered = sample[2] - first[2];

        if (dropped >= MIN_DROPPED_FRAMES && dropped * 100 >= (dropped + rendered) * MAX_DROPPED_PERCENT) {
            stepDown(player, format, dropped, rendered);
        }
    }

    private void stepDown(PlayerManager player, Format format, long dropped, long rendered) {
        Log.d(TAG, "%s frames of %s dropped in %s s: %s", dropped, dropped + rendered, WINDOW_MS / 1_000, format);

        if (!VideoLimits.instance(getContext()).markTooHeavy(format)) {
            // 1080p and less: nothing lighter is chosen for it (the cause is elsewhere, e.g. the network)
            settle();
            return;
        }

        MessageHelpers.showMessage(getContext(), getContext().getString(R.string.playback_too_heavy, VideoLimits.getLabel(format)));
        settle();
        // The same spot, the tracks chosen again: the heavy one is left out now
        player.reloadPlayback();
    }
}
