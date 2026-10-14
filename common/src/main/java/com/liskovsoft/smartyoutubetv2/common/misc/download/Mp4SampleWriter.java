package com.liskovsoft.smartyoutubetv2.common.misc.download;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import androidx.annotation.RequiresApi;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.mediacodec.MediaFormatUtil;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * MP4 (H.264 + AAC) by means of the writer of the system
 */
@RequiresApi(21)
public class Mp4SampleWriter implements SampleWriter {
    private final MediaMuxer mMuxer;
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private boolean mStarted;

    public Mp4SampleWriter(MediaMuxer muxer) {
        mMuxer = muxer;
    }

    @Override
    public int addTrack(Format format) throws IOException {
        try {
            return mMuxer.addTrack(toMediaFormat(format));
        } catch (RuntimeException e) {
            throw new IOException("This device can't write " + format.sampleMimeType + " into mp4", e);
        }
    }

    @Override
    public void start() throws IOException {
        try {
            mMuxer.start();
            mStarted = true;
        } catch (RuntimeException e) {
            throw new IOException("Can't start the mp4 writer", e);
        }
    }

    @SuppressLint("WrongConstant")
    @Override
    public void writeSample(int track, ByteBuffer data, long timeUs, boolean isKeyFrame) throws IOException {
        try {
            mBufferInfo.set(data.position(), data.remaining(), timeUs, isKeyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            mMuxer.writeSampleData(track, data, mBufferInfo);
        } catch (RuntimeException e) {
            throw new IOException("Can't write to the file (no space left?)", e);
        }
    }

    @Override
    public void finish() throws IOException {
        try {
            // NOTE: this is where the index of the file is written
            mMuxer.stop();
            mStarted = false;
        } catch (RuntimeException e) {
            throw new IOException("Can't finish the file", e);
        }
    }

    @Override
    public void release() {
        try {
            if (mStarted) {
                mStarted = false;
                mMuxer.stop();
            }
        } catch (RuntimeException e) {
            // Nothing was written, e.g.
        }

        try {
            mMuxer.release();
        } catch (RuntimeException e) {
            // Nothing to do
        }
    }

    private static MediaFormat toMediaFormat(Format format) {
        MediaFormat result = new MediaFormat();
        result.setString(MediaFormat.KEY_MIME, format.sampleMimeType);

        if (MimeTypes.isVideo(format.sampleMimeType)) {
            result.setInteger(MediaFormat.KEY_WIDTH, format.width);
            result.setInteger(MediaFormat.KEY_HEIGHT, format.height);
            MediaFormatUtil.maybeSetFloat(result, MediaFormat.KEY_FRAME_RATE, format.frameRate);
            MediaFormatUtil.maybeSetColorInfo(result, format.colorInfo);
        } else {
            result.setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channelCount);
            result.setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate);
        }

        MediaFormatUtil.maybeSetInteger(result, MediaFormat.KEY_MAX_INPUT_SIZE, format.maxInputSize);
        MediaFormatUtil.setCsdBuffers(result, format.initializationData);

        return result;
    }
}
