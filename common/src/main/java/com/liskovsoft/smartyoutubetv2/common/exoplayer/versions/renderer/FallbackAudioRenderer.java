package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.RendererConfiguration;
import com.google.android.exoplayer2.source.SampleStream;
import com.google.android.exoplayer2.util.MediaClock;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.IOException;

/**
 * One audio renderer made of two.<br/>
 * <b>The main one</b> is the usual renderer: the decoders of the device, or the sound is handed
 * undecoded to a receiver / sound bar that says it understands it (that's how Dolby Atmos and DTS:X
 * reach the speakers untouched).<br/>
 * <b>The software one</b> (FFmpeg) takes what the main one can't play: Dolby Digital, Dolby Digital Plus,
 * Dolby TrueHD, DTS, DTS-HD on a device that has no decoder for them.<br/>
 * <br/>
 * NOTE: one renderer and not two in a row, because the app counts on the order video, audio, subtitles.
 */
public class FallbackAudioRenderer implements Renderer, RendererCapabilities {
    private static final String TAG = FallbackAudioRenderer.class.getSimpleName();
    private final Renderer mMain;
    private final Renderer mSoftware;
    /**
     * The one that plays (or played last)
     */
    private Renderer mActive;

    public FallbackAudioRenderer(Renderer main, Renderer software) {
        mMain = main;
        mSoftware = software;
        mActive = main;
    }

    /**
     * @return whether the sound is decoded by the software decoder at the moment
     */
    public boolean isSoftwareDecoderActive() {
        return mActive == mSoftware && mActive.getState() != STATE_DISABLED;
    }

    private Renderer choose(Format[] formats) {
        if (formats == null || formats.length == 0) {
            return mMain;
        }

        try {
            if (getSupport(mMain, formats[0]) != FORMAT_HANDLED && getSupport(mSoftware, formats[0]) == FORMAT_HANDLED) {
                return mSoftware;
            }
        } catch (ExoPlaybackException e) {
            Log.e(TAG, "Can't ask the renderers about %s: %s", formats[0].sampleMimeType, e.getMessage());
        }

        return mMain;
    }

    private static int getSupport(Renderer renderer, Format format) throws ExoPlaybackException {
        return renderer.getCapabilities().supportsFormat(format) & FORMAT_SUPPORT_MASK;
    }

    // RendererCapabilities

    @Override
    public int getTrackType() {
        return C.TRACK_TYPE_AUDIO;
    }

    @Override
    public int supportsFormat(Format format) throws ExoPlaybackException {
        int main = mMain.getCapabilities().supportsFormat(format);

        if ((main & FORMAT_SUPPORT_MASK) == FORMAT_HANDLED) {
            return main;
        }

        int software = mSoftware.getCapabilities().supportsFormat(format);

        return (software & FORMAT_SUPPORT_MASK) == FORMAT_HANDLED ? software : main;
    }

    @Override
    public int supportsMixedMimeTypeAdaptation() throws ExoPlaybackException {
        return ADAPTIVE_NOT_SEAMLESS;
    }

    // Renderer

    @Override
    public RendererCapabilities getCapabilities() {
        return this;
    }

    @Override
    public void setIndex(int index) {
        mMain.setIndex(index);
        mSoftware.setIndex(index);
    }

    @Override
    public MediaClock getMediaClock() {
        return mActive.getMediaClock();
    }

    @Override
    public int getState() {
        return mActive.getState();
    }

    @Override
    public void enable(RendererConfiguration configuration, Format[] formats, SampleStream stream, long positionUs, boolean joining, long offsetUs)
            throws ExoPlaybackException {
        mActive = choose(formats);

        if (mActive == mSoftware && formats.length > 0) {
            Log.d(TAG, "Software decoder for %s", formats[0].sampleMimeType);
        }

        mActive.enable(configuration, formats, stream, positionUs, joining, offsetUs);
    }

    @Override
    public void start() throws ExoPlaybackException {
        mActive.start();
    }

    @Override
    public void replaceStream(Format[] formats, SampleStream stream, long offsetUs) throws ExoPlaybackException {
        // NOTE: the next item of a playlist. The app plays one item at a time, so the formats are of the same kind.
        mActive.replaceStream(formats, stream, offsetUs);
    }

    @Override
    public SampleStream getStream() {
        return mActive.getStream();
    }

    @Override
    public boolean hasReadStreamToEnd() {
        return mActive.hasReadStreamToEnd();
    }

    @Override
    public long getReadingPositionUs() {
        return mActive.getReadingPositionUs();
    }

    @Override
    public void setCurrentStreamFinal() {
        mActive.setCurrentStreamFinal();
    }

    @Override
    public boolean isCurrentStreamFinal() {
        return mActive.isCurrentStreamFinal();
    }

    @Override
    public void maybeThrowStreamError() throws IOException {
        mActive.maybeThrowStreamError();
    }

    @Override
    public void resetPosition(long positionUs) throws ExoPlaybackException {
        mActive.resetPosition(positionUs);
    }

    @Override
    public void setOperatingRate(float operatingRate) throws ExoPlaybackException {
        mMain.setOperatingRate(operatingRate);
        mSoftware.setOperatingRate(operatingRate);
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
        mActive.render(positionUs, elapsedRealtimeUs);
    }

    @Override
    public boolean isReady() {
        return mActive.isReady();
    }

    @Override
    public boolean isEnded() {
        return mActive.isEnded();
    }

    @Override
    public void stop() throws ExoPlaybackException {
        mActive.stop();
    }

    @Override
    public void disable() {
        mActive.disable();
    }

    @Override
    public void reset() {
        mMain.reset();
        mSoftware.reset();
    }

    /**
     * Volume, audio attributes...: both have to know, whichever plays next
     */
    @Override
    public void handleMessage(int messageType, Object message) throws ExoPlaybackException {
        mMain.handleMessage(messageType, message);
        mSoftware.handleMessage(messageType, message);
    }
}
