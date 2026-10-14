package com.liskovsoft.smartyoutubetv2.common.misc.download;

import com.google.android.exoplayer2.Format;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Puts the samples of several tracks into one media file
 */
public interface SampleWriter {
    /**
     * @return index of the track for {@link #writeSample}
     */
    int addTrack(Format format) throws IOException;

    /**
     * No more tracks: the samples follow
     */
    void start() throws IOException;

    /**
     * @param data the sample: from the position to the limit of the buffer
     */
    void writeSample(int track, ByteBuffer data, long timeUs, boolean isKeyFrame) throws IOException;

    /**
     * No more samples: complete the file (index, duration)
     */
    void finish() throws IOException;

    /**
     * Close the file. Can be called at any moment (the file is broken unless {@link #finish} was called).
     */
    void release();
}
