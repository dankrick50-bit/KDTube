package com.liskovsoft.smartyoutubetv2.common.misc.download;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * WebM (VP9/VP8 + Opus).<br/>
 * <br/>
 * The writer of the system can't be used: no Opus before Android 10, no AV1 at all.
 * The container is simple enough: a header, the descriptions of the tracks, then the clusters
 * of the samples (a cluster starts with a key frame) and the index of the clusters at the end.
 */
public class WebmSampleWriter implements SampleWriter {
    private static final int ID_EBML = 0x1A45DFA3;
    private static final int ID_EBML_VERSION = 0x4286;
    private static final int ID_EBML_READ_VERSION = 0x42F7;
    private static final int ID_EBML_MAX_ID_LENGTH = 0x42F2;
    private static final int ID_EBML_MAX_SIZE_LENGTH = 0x42F3;
    private static final int ID_DOC_TYPE = 0x4282;
    private static final int ID_DOC_TYPE_VERSION = 0x4287;
    private static final int ID_DOC_TYPE_READ_VERSION = 0x4285;
    private static final int ID_VOID = 0xEC;
    private static final int ID_SEGMENT = 0x18538067;
    private static final int ID_SEEK_HEAD = 0x114D9B74;
    private static final int ID_SEEK = 0x4DBB;
    private static final int ID_SEEK_ID = 0x53AB;
    private static final int ID_SEEK_POSITION = 0x53AC;
    private static final int ID_INFO = 0x1549A966;
    private static final int ID_TIMECODE_SCALE = 0x2AD7B1;
    private static final int ID_DURATION = 0x4489;
    private static final int ID_MUXING_APP = 0x4D80;
    private static final int ID_WRITING_APP = 0x5741;
    private static final int ID_TRACKS = 0x1654AE6B;
    private static final int ID_TRACK_ENTRY = 0xAE;
    private static final int ID_TRACK_NUMBER = 0xD7;
    private static final int ID_TRACK_UID = 0x73C5;
    private static final int ID_TRACK_TYPE = 0x83;
    private static final int ID_FLAG_LACING = 0x9C;
    private static final int ID_CODEC_ID = 0x86;
    private static final int ID_CODEC_PRIVATE = 0x63A2;
    private static final int ID_CODEC_DELAY = 0x56AA;
    private static final int ID_SEEK_PRE_ROLL = 0x56BB;
    private static final int ID_VIDEO = 0xE0;
    private static final int ID_PIXEL_WIDTH = 0xB0;
    private static final int ID_PIXEL_HEIGHT = 0xBA;
    private static final int ID_AUDIO = 0xE1;
    private static final int ID_SAMPLING_FREQUENCY = 0xB5;
    private static final int ID_CHANNELS = 0x9F;
    private static final int ID_CLUSTER = 0x1F43B675;
    private static final int ID_TIMECODE = 0xE7;
    private static final int ID_SIMPLE_BLOCK = 0xA3;
    private static final int ID_CUES = 0x1C53BB6B;
    private static final int ID_CUE_POINT = 0xBB;
    private static final int ID_CUE_TIME = 0xB3;
    private static final int ID_CUE_TRACK_POSITIONS = 0xB7;
    private static final int ID_CUE_TRACK = 0xF7;
    private static final int ID_CUE_CLUSTER_POSITION = 0xF1;
    private static final int TRACK_TYPE_VIDEO = 1;
    private static final int TRACK_TYPE_AUDIO = 2;
    /**
     * The times of the file are in milliseconds
     */
    private static final long TIMECODE_SCALE_NS = 1_000_000;
    /**
     * The time of a sample is stored as 16 bits relative to its cluster
     */
    private static final long MAX_CLUSTER_DURATION_MS = 30_000;
    /**
     * When there's no video to align the clusters to
     */
    private static final long AUDIO_CLUSTER_DURATION_MS = 5_000;
    private static final long OPUS_SEEK_PRE_ROLL_NS = 80_000_000;
    private static final int SEEK_HEAD_RESERVED_SIZE = 96;
    private static final int OUTPUT_BUFFER_SIZE = 256 * 1024;
    private static final String APP_NAME = "KDTube";
    private final FileChannel mChannel;
    private final Closeable mOwner;
    private final ByteBuffer mOutput = ByteBuffer.allocate(OUTPUT_BUFFER_SIZE);
    private final List<Format> mTracks = new ArrayList<>();
    private final List<long[]> mCues = new ArrayList<>(); // time (ms), position of the cluster in the segment
    private int mVideoTrack = -1;
    private long mPosition;
    private long mSegmentDataStart;
    private long mSeekHeadPosition;
    private long mDurationValuePosition;
    private long mInfoPosition;
    private long mTracksPosition;
    private long mClusterSizePosition = -1;
    private long mClusterDataStart;
    private long mClusterTimeMs;
    private long mDurationMs;
    private boolean mStarted;
    private boolean mFinished;

    /**
     * @param channel should be writable and seekable, positioned at the start of an empty file
     * @param owner the thing that should be closed together with the channel (may be null)
     */
    public WebmSampleWriter(FileChannel channel, Closeable owner) {
        mChannel = channel;
        mOwner = owner;
    }

    public static boolean isSupported(String sampleMimeType) {
        return getCodecId(sampleMimeType) != null;
    }

    @Override
    public int addTrack(Format format) throws IOException {
        if (mStarted) {
            throw new IOException("The tracks should be added before the start");
        }

        if (getCodecId(format.sampleMimeType) == null) {
            throw new IOException("Can't write " + format.sampleMimeType + " into webm");
        }

        if (MimeTypes.isVideo(format.sampleMimeType) && mVideoTrack == -1) {
            mVideoTrack = mTracks.size();
        }

        mTracks.add(format);
        return mTracks.size() - 1;
    }

    @Override
    public void start() throws IOException {
        mStarted = true;

        Element header = new Element(ID_EBML);
        header.addUInt(ID_EBML_VERSION, 1);
        header.addUInt(ID_EBML_READ_VERSION, 1);
        header.addUInt(ID_EBML_MAX_ID_LENGTH, 4);
        header.addUInt(ID_EBML_MAX_SIZE_LENGTH, 8);
        header.addString(ID_DOC_TYPE, "webm");
        header.addUInt(ID_DOC_TYPE_VERSION, 4);
        header.addUInt(ID_DOC_TYPE_READ_VERSION, 2);
        write(header.toBytes());

        // The size of the segment isn't known yet: "unknown" is a valid value (till the end of the file)
        writeId(ID_SEGMENT);
        write(new byte[] {0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        mSegmentDataStart = mPosition;

        // Room for the table of contents (where the index is). Written at the end.
        mSeekHeadPosition = mPosition;
        writeVoid(SEEK_HEAD_RESERVED_SIZE);

        mInfoPosition = mPosition;
        Element info = new Element(ID_INFO);
        info.addUInt(ID_TIMECODE_SCALE, TIMECODE_SCALE_NS);
        info.addString(ID_MUXING_APP, APP_NAME);
        info.addString(ID_WRITING_APP, APP_NAME);
        int durationOffset = info.size();
        info.addFloat(ID_DURATION, 0); // replaced at the end
        byte[] infoBytes = info.toBytes();
        // id of the info (4) + its size (see Element.toBytes) + offset in the content + id of the duration (2) + its size (1)
        mDurationValuePosition = mPosition + (infoBytes.length - info.size()) + durationOffset + 3;
        write(infoBytes);

        mTracksPosition = mPosition;
        Element tracks = new Element(ID_TRACKS);

        for (int i = 0; i < mTracks.size(); i++) {
            tracks.add(createTrackEntry(i + 1, mTracks.get(i)));
        }

        write(tracks.toBytes());
    }

    @Override
    public void writeSample(int track, ByteBuffer data, long timeUs, boolean isKeyFrame) throws IOException {
        if (!mStarted || mFinished) {
            throw new IOException("The writer isn't started");
        }

        long timeMs = Math.max(0, Math.round(timeUs / 1_000d));
        boolean isVideo = track == mVideoTrack;
        boolean newCluster = mClusterSizePosition == -1;
        // A place to seek to: the decoding can be started from it
        boolean isSeekPoint = mVideoTrack == -1 || (isVideo && isKeyFrame);

        if (!newCluster) {
            long relativeMs = timeMs - mClusterTimeMs;

            // NOTE: a bit of audio that is earlier than the key frame of its cluster is fine (the relative time is signed)
            if (Math.abs(relativeMs) > MAX_CLUSTER_DURATION_MS) {
                newCluster = true;
            } else if (mVideoTrack != -1) {
                newCluster = isVideo && isKeyFrame && relativeMs > 0;
            } else {
                newCluster = relativeMs >= AUDIO_CLUSTER_DURATION_MS;
            }
        }

        if (newCluster) {
            startCluster(timeMs, isSeekPoint);
        }

        int size = data.remaining();
        long relativeMs = timeMs - mClusterTimeMs;

        // Track number (1 byte, tracks 1-126) + relative time (2 bytes) + flags (1 byte)
        writeId(ID_SIMPLE_BLOCK);
        writeSize(size + 4);
        mOutput.put((byte) (0x80 | (track + 1)));
        mOutput.putShort((short) relativeMs);
        // NOTE: every audio frame can be decoded on its own
        mOutput.put((byte) (isKeyFrame || !isVideo ? 0x80 : 0));
        mPosition += 4;
        write(data);

        mDurationMs = Math.max(mDurationMs, timeMs);
    }

    @Override
    public void finish() throws IOException {
        if (!mStarted || mFinished) {
            return;
        }

        endCluster();

        long cuesPosition = mPosition;
        Element cues = new Element(ID_CUES);
        int cueTrack = (mVideoTrack != -1 ? mVideoTrack : 0) + 1;

        for (long[] cue : mCues) {
            Element positions = new Element(ID_CUE_TRACK_POSITIONS);
            positions.addUInt(ID_CUE_TRACK, cueTrack);
            positions.addUInt(ID_CUE_CLUSTER_POSITION, cue[1]);
            Element point = new Element(ID_CUE_POINT);
            point.addUInt(ID_CUE_TIME, cue[0]);
            point.add(positions);
            cues.add(point);
        }

        write(cues.toBytes());
        flush();

        // Now the things that weren't known at the start
        Element seekHead = new Element(ID_SEEK_HEAD);
        seekHead.add(createSeek(ID_INFO, mInfoPosition - mSegmentDataStart));
        seekHead.add(createSeek(ID_TRACKS, mTracksPosition - mSegmentDataStart));
        seekHead.add(createSeek(ID_CUES, cuesPosition - mSegmentDataStart));
        byte[] seekHeadBytes = seekHead.toBytes();

        // NOTE: a void element is 2 bytes at least
        if (seekHeadBytes.length <= SEEK_HEAD_RESERVED_SIZE - 2 || seekHeadBytes.length == SEEK_HEAD_RESERVED_SIZE) {
            ByteBuffer seekHeadBuffer = ByteBuffer.allocate(SEEK_HEAD_RESERVED_SIZE);
            seekHeadBuffer.put(seekHeadBytes);

            if (seekHeadBuffer.hasRemaining()) {
                putVoid(seekHeadBuffer, seekHeadBuffer.remaining());
            }

            seekHeadBuffer.flip();
            writeAt(seekHeadBuffer, mSeekHeadPosition);
        }

        // The last frame is shown for some time too. Not known here: one more millisecond is enough.
        ByteBuffer duration = ByteBuffer.allocate(8);
        duration.putDouble(mDurationMs + 1);
        duration.flip();
        writeAt(duration, mDurationValuePosition);

        ByteBuffer segmentSize = ByteBuffer.allocate(8);
        segmentSize.putLong((mPosition - mSegmentDataStart) | (1L << 56));
        segmentSize.flip();
        writeAt(segmentSize, mSegmentDataStart - 8);

        mChannel.force(false);
        mFinished = true;
    }

    @Override
    public void release() {
        try {
            mChannel.close();
        } catch (IOException e) {
            // Nothing to do
        }

        if (mOwner != null) {
            try {
                mOwner.close();
            } catch (IOException e) {
                // Nothing to do
            }
        }
    }

    private void startCluster(long timeMs, boolean isSeekPoint) throws IOException {
        endCluster();

        if (isSeekPoint) {
            mCues.add(new long[] {timeMs, mPosition - mSegmentDataStart});
        }

        writeId(ID_CLUSTER);
        mClusterSizePosition = mPosition;
        // The size is written when the cluster is complete
        write(new byte[] {0x01, 0, 0, 0, 0, 0, 0, 0});
        mClusterDataStart = mPosition;
        mClusterTimeMs = timeMs;

        Element timecode = new Element(0);
        timecode.addUInt(ID_TIMECODE, timeMs);
        write(timecode.content());
    }

    private void endCluster() throws IOException {
        if (mClusterSizePosition == -1) {
            return;
        }

        flush();

        ByteBuffer size = ByteBuffer.allocate(8);
        size.putLong((mPosition - mClusterDataStart) | (1L << 56));
        size.flip();
        writeAt(size, mClusterSizePosition);
        mClusterSizePosition = -1;
    }

    private Element createTrackEntry(int number, Format format) {
        boolean isVideo = MimeTypes.isVideo(format.sampleMimeType);
        Element entry = new Element(ID_TRACK_ENTRY);
        entry.addUInt(ID_TRACK_NUMBER, number);
        entry.addUInt(ID_TRACK_UID, number);
        entry.addUInt(ID_TRACK_TYPE, isVideo ? TRACK_TYPE_VIDEO : TRACK_TYPE_AUDIO);
        entry.addUInt(ID_FLAG_LACING, 0);
        entry.addString(ID_CODEC_ID, getCodecId(format.sampleMimeType));

        if (MimeTypes.AUDIO_OPUS.equals(format.sampleMimeType)) {
            byte[] codecPrivate = format.initializationData != null && !format.initializationData.isEmpty() ? format.initializationData.get(0) : null;

            if (codecPrivate != null) {
                entry.addBinary(ID_CODEC_PRIVATE, codecPrivate);
            }

            // The header of the stream ("OpusHead"): the number of the samples to skip is at the offset 10
            int preSkip = codecPrivate != null && codecPrivate.length >= 12 ? (codecPrivate[10] & 0xFF) | ((codecPrivate[11] & 0xFF) << 8) : 0;
            entry.addUInt(ID_CODEC_DELAY, preSkip * 1_000_000_000L / 48_000);
            entry.addUInt(ID_SEEK_PRE_ROLL, OPUS_SEEK_PRE_ROLL_NS);
        }

        if (isVideo) {
            Element video = new Element(ID_VIDEO);
            video.addUInt(ID_PIXEL_WIDTH, format.width);
            video.addUInt(ID_PIXEL_HEIGHT, format.height);
            entry.add(video);
        } else {
            Element audio = new Element(ID_AUDIO);
            audio.addFloat(ID_SAMPLING_FREQUENCY, format.sampleRate > 0 ? format.sampleRate : 48_000);
            audio.addUInt(ID_CHANNELS, format.channelCount > 0 ? format.channelCount : 2);
            entry.add(audio);
        }

        return entry;
    }

    private static Element createSeek(int id, long position) {
        Element seek = new Element(ID_SEEK);
        seek.addBinary(ID_SEEK_ID, new byte[] {(byte) (id >>> 24), (byte) (id >>> 16), (byte) (id >>> 8), (byte) id});
        seek.addUInt(ID_SEEK_POSITION, position);
        return seek;
    }

    private static String getCodecId(String sampleMimeType) {
        if (sampleMimeType == null) {
            return null;
        }

        switch (sampleMimeType) {
            case MimeTypes.VIDEO_VP9:
                return "V_VP9";
            case MimeTypes.VIDEO_VP8:
                return "V_VP8";
            case MimeTypes.AUDIO_OPUS:
                return "A_OPUS";
            default:
                return null;
        }
    }

    // region: output

    private void write(byte[] data) throws IOException {
        write(ByteBuffer.wrap(data));
    }

    private void write(ByteBuffer data) throws IOException {
        int size = data.remaining();

        if (size > mOutput.remaining()) {
            flush();
        }

        if (size > mOutput.capacity()) {
            while (data.hasRemaining()) {
                mChannel.write(data);
            }
        } else {
            mOutput.put(data);
        }

        mPosition += size;
    }

    private void writeId(int id) throws IOException {
        ensureRoom(16);
        mPosition += putId(mOutput, id);
    }

    private void writeSize(long size) throws IOException {
        ensureRoom(16);
        mPosition += putSize(mOutput, size);
    }

    private void writeVoid(int totalSize) throws IOException {
        ensureRoom(totalSize);
        putVoid(mOutput, totalSize);
        mPosition += totalSize;
    }

    private void ensureRoom(int size) throws IOException {
        if (mOutput.remaining() < size) {
            flush();
        }
    }

    private void flush() throws IOException {
        mOutput.flip();

        while (mOutput.hasRemaining()) {
            mChannel.write(mOutput);
        }

        mOutput.clear();
    }

    /**
     * NOTE: everything should be flushed before
     */
    private void writeAt(ByteBuffer data, long position) throws IOException {
        while (data.hasRemaining()) {
            position += mChannel.write(data, position);
        }
    }

    private static int putId(ByteBuffer buffer, int id) {
        int length = (id & 0xFF000000) != 0 ? 4 : (id & 0xFF0000) != 0 ? 3 : (id & 0xFF00) != 0 ? 2 : 1;

        for (int i = length - 1; i >= 0; i--) {
            buffer.put((byte) (id >>> (8 * i)));
        }

        return length;
    }

    private static int putSize(ByteBuffer buffer, long size) {
        // The length of the number is the position of its first bit that is set. All ones is reserved.
        int length = 1;

        while (length < 8 && size >= (1L << (7 * length)) - 1) {
            length++;
        }

        long value = size | (1L << (7 * length));

        for (int i = length - 1; i >= 0; i--) {
            buffer.put((byte) (value >>> (8 * i)));
        }

        return length;
    }

    private static void putVoid(ByteBuffer buffer, int totalSize) {
        // id (1 byte) + size + filler
        int sizeLength = totalSize - 2 < 127 ? 1 : 8;
        buffer.put((byte) ID_VOID);

        if (sizeLength == 1) {
            buffer.put((byte) (0x80 | (totalSize - 2)));
        } else {
            buffer.putLong((totalSize - 9) | (1L << 56));
        }

        for (int i = totalSize - 1 - sizeLength; i > 0; i--) {
            buffer.put((byte) 0);
        }
    }

    // endregion: output

    /**
     * A small element that is built in the memory (the headers and the index)
     */
    private static final class Element {
        private final int mId;
        private final ByteArrayOutputStream mContent = new ByteArrayOutputStream();
        private final ByteBuffer mTemp = ByteBuffer.allocate(16);

        Element(int id) {
            mId = id;
        }

        int size() {
            return mContent.size();
        }

        byte[] content() {
            return mContent.toByteArray();
        }

        void add(Element child) {
            byte[] bytes = child.toBytes();
            mContent.write(bytes, 0, bytes.length);
        }

        void addUInt(int id, long value) {
            int length = 1;

            while (length < 8 && (value >>> (8 * length)) != 0) {
                length++;
            }

            addHeader(id, length);

            for (int i = length - 1; i >= 0; i--) {
                mContent.write((int) (value >>> (8 * i)) & 0xFF);
            }
        }

        void addFloat(int id, double value) {
            addHeader(id, 8);
            mTemp.clear();
            mTemp.putDouble(value);
            mContent.write(mTemp.array(), 0, 8);
        }

        void addString(int id, String value) {
            addBinary(id, value.getBytes(StandardCharsets.UTF_8));
        }

        void addBinary(int id, byte[] value) {
            addHeader(id, value.length);
            mContent.write(value, 0, value.length);
        }

        private void addHeader(int id, long size) {
            mTemp.clear();
            putId(mTemp, id);
            putSize(mTemp, size);
            mContent.write(mTemp.array(), 0, mTemp.position());
        }

        byte[] toBytes() {
            ByteBuffer header = ByteBuffer.allocate(16);
            putId(header, mId);
            putSize(header, mContent.size());
            byte[] result = new byte[header.position() + mContent.size()];
            System.arraycopy(header.array(), 0, result, 0, header.position());
            System.arraycopy(mContent.toByteArray(), 0, result, header.position(), mContent.size());
            return result;
        }
    }
}
