package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

/**
 * The sound options of the player ("Picture &amp; sound"), applied to the decoded audio:<br/>
 * <br/>
 * <b>levelling</b>: a compressor. Loud passages (adverts, explosions) are pulled down and the whole
 * signal is brought back up, so quiet speech and loud scenes sit closer together.
 * "Night" squeezes them much closer, for watching at low volume.<br/>
 * <b>dialogue boost</b>: lifts the band speech lives in and eases the rumble below it.<br/>
 * <b>wide stereo</b>: makes the difference between the left and the right channel bigger,
 * the sound seems to come from beyond the speakers. Stereo only.<br/>
 * <b>virtual surround</b>: 5.1 / 7.1 sound (Dolby Digital, DTS...) mixed down to two channels in a way that
 * keeps the room: the rear channels arrive a little later and partly in opposite phase on the other side,
 * which the ear places wide and behind. For TV speakers, sound bars without rear speakers and headphones.<br/>
 * <br/>
 * The numbers of the first two are the same as in the player of Extreme InfiniTV.<br/>
 * <br/>
 * NOTE: doesn't touch the sound that is passed to a receiver undecoded (Dolby, DTS).
 */
public class SoundEnhanceProcessor extends BaseAudioProcessor {
    public static final int LEVELLING_OFF = 0;
    public static final int LEVELLING_ON = 1;
    public static final int LEVELLING_NIGHT = 2;
    public static final int BASS_OFF = 0;
    public static final int BASS_LIGHT = 1;
    public static final int BASS_STRONG = 2;
    /**
     * Bass boost: a shelf under the kick drum and the bass line (below the voices: they stay clear), and nothing
     * below what speakers play: the energy under 25 Hz would only make small speakers distort. What it lifts is
     * taken back in the level first (a third of it: the sound shouldn't get weaker), and a limiter keeps the loudest moments clean.
     */
    private static final float BASS_HZ = 90;
    private static final float BASS_LIGHT_DB = 4;
    private static final float BASS_STRONG_DB = 7;
    private static final float SUBSONIC_HZ = 25;
    private static final float SPEECH_HZ = 2_600;
    private static final float SPEECH_Q = 0.8f;
    private static final float SPEECH_LIFT_DB = 6;
    private static final float RUMBLE_HZ = 160;
    private static final float RUMBLE_CUT_DB = -3;
    /**
     * Headroom for the speech lift when there's no compressor to tame the peaks
     */
    private static final float DIALOGUE_TRIM = 0.8f;
    private static final float STEREO_WIDTH = 1.7f;
    /**
     * The wider sound is louder: keep the peaks where they were
     */
    private static final float WIDE_TRIM = 0.85f;
    // Virtual surround
    private static final float CENTER_MIX = 0.7071f;
    private static final float CENTER_MIX_DIALOGUE = 0.92f;
    private static final float LFE_MIX = 0.5f;
    private static final float REAR_MIX = 0.8f;
    /**
     * The share of a rear channel that goes to the other side, in opposite phase
     */
    private static final float REAR_CROSS = 0.4f;
    private static final float REAR_DELAY_SEC = 0.012f;
    /**
     * Many channels into two: keep the peaks below the ceiling
     */
    private static final float DOWNMIX_TRIM = 0.6f;
    private static final int MAX_CHANNELS = 8;
    private static final float MIN_LEVEL = 1e-5f; // -100 dB
    private static final float LN10_DIV_20 = (float) (Math.log(10) / 20);
    private static volatile int sLevelling = LEVELLING_OFF;
    private static volatile boolean sIsDialogueBoostEnabled;
    private static volatile boolean sIsWideStereoEnabled;
    private static volatile boolean sIsVirtualSurroundEnabled;
    private static volatile int sBassBoost = BASS_OFF;
    /**
     * Changes on every change of the options (they are set from another thread)
     */
    private static volatile int sOptionsVersion;
    private int mOptionsVersion = -1;
    private int mLevelling;
    private boolean mDialogue;
    private boolean mWide;
    private int mBass;
    private float mBassTrim = 1;
    private final float[] mBassShelf = new float[5];
    private final float[] mSubsonic = new float[5];
    private float[] mBassState = new float[0];
    private float[] mSubsonicState = new float[0];
    // The compressor
    private float mThresholdDb;
    private float mKneeDb;
    private float mRatio;
    private float mAttackCoeff;
    private float mReleaseCoeff;
    private float mMakeupDb;
    private float mReductionDb;
    // The filters: coefficients (b0 b1 b2 a1 a2) and the state of each channel (z1 z2)
    private final float[] mSpeech = new float[5];
    private final float[] mRumble = new float[5];
    private float[] mSpeechState = new float[0];
    private float[] mRumbleState = new float[0];
    private float[] mFrame = new float[0];
    // Virtual surround: decided when the output is set up (the number of the channels is fixed after that)
    private boolean mDownmix;
    private float[] mRearLeft = new float[0];
    private float[] mRearRight = new float[0];
    private int mRearPos;

    public static void setOptions(int levelling, boolean dialogueBoost, boolean wideStereo, boolean virtualSurround) {
        setOptions(levelling, dialogueBoost, wideStereo, virtualSurround, sBassBoost);
    }

    /**
     * @param bassBoost one of BASS_*
     */
    public static void setOptions(int levelling, boolean dialogueBoost, boolean wideStereo, boolean virtualSurround, int bassBoost) {
        sBassBoost = bassBoost;
        sLevelling = levelling;
        sIsDialogueBoostEnabled = dialogueBoost;
        sIsWideStereoEnabled = wideStereo;
        sIsVirtualSurroundEnabled = virtualSurround;
        sOptionsVersion++;
    }

    @Override
    public boolean configure(int sampleRateHz, int channelCount, @C.PcmEncoding int encoding) throws UnhandledFormatException {
        if (encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledFormatException(sampleRateHz, channelCount, encoding);
        }

        boolean changed = setInputFormat(sampleRateHz, channelCount, encoding);
        boolean downmix = sIsVirtualSurroundEnabled && channelCount > 2 && channelCount <= MAX_CHANNELS;

        if (downmix != mDownmix) {
            mDownmix = downmix;
            changed = true;
        }

        if (changed) {
            mOptionsVersion = -1; // the coefficients depend on the sample rate
        }

        return changed;
    }

    @Override
    public int getOutputChannelCount() {
        return mDownmix ? 2 : channelCount;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int size = inputBuffer.remaining();

        if (size == 0) {
            return;
        }

        if (mOptionsVersion != sOptionsVersion) {
            applyOptions();
        }

        ByteBuffer output = replaceOutputBuffer(size);

        if (mLevelling == LEVELLING_OFF && !mDialogue && !mWide && !mDownmix && mBass == BASS_OFF) {
            output.put(inputBuffer);
            output.flip();
            return;
        }

        int inputChannels = channelCount;
        int channels = mDownmix ? 2 : inputChannels;
        int frames = size / (2 * inputChannels);
        float[] frame = mFrame;
        boolean wide = mWide && inputChannels == 2;
        boolean bass = mBass != BASS_OFF;
        // Volume levelling compresses; the bass boost alone gets a limiter (the same machinery, other numbers)
        boolean compress = mLevelling != LEVELLING_OFF || bass;
        boolean levelling = mLevelling != LEVELLING_OFF;
        float trim = (mDialogue && !levelling ? DIALOGUE_TRIM : 1) * (wide && !levelling ? WIDE_TRIM : 1) * (bass ? mBassTrim : 1);

        for (int i = 0; i < frames; i++) {
            float peak = 0;

            for (int c = 0; c < inputChannels; c++) {
                frame[c] = inputBuffer.getShort() / 32768f;
            }

            if (mDownmix) {
                downmix(frame, inputChannels);
            }

            if (mDialogue) {
                for (int c = 0; c < channels; c++) {
                    frame[c] = filter(mRumble, mRumbleState, c, filter(mSpeech, mSpeechState, c, frame[c]));
                }
            }

            if (wide) {
                float mid = (frame[0] + frame[1]) * 0.5f;
                float side = (frame[0] - frame[1]) * 0.5f * STEREO_WIDTH;
                frame[0] = mid + side;
                frame[1] = mid - side;
            }

            if (bass) {
                for (int c = 0; c < channels; c++) {
                    frame[c] = filter(mSubsonic, mSubsonicState, c, filter(mBassShelf, mBassState, c, frame[c] * trim));
                }
            }

            // NOTE: with the bass boost the trim is applied before the filters (headroom for the lift)
            float gain = bass ? 1 : trim;

            if (compress) {
                // One gain for all the channels: the picture of the sound stays where it is
                for (int c = 0; c < channels; c++) {
                    peak = Math.max(peak, Math.abs(frame[c]));
                }

                gain = nextGain(peak);
            }

            for (int c = 0; c < channels; c++) {
                float sample = frame[c] * gain;
                // The last resort: no wrap around
                output.putShort((short) (sample > 1 ? 32767 : sample < -1 ? -32768 : Math.round(sample * 32767)));
            }
        }

        // An odd tail (shouldn't happen): as is
        if (inputBuffer.hasRemaining()) {
            if (mDownmix) {
                inputBuffer.position(inputBuffer.limit());
            } else {
                output.put(inputBuffer);
            }
        }

        output.flip();
    }

    /**
     * Turns a frame of 3 to 8 channels into two (the first two values of the frame).<br/>
     * The order of the channels is the one of Android: FL FR FC LFE BL BR SL SR (7.1), FL FR FC LFE BC SL SR (6.1),
     * FL FR FC LFE BL BR (5.1), FL FR FC BL BR, FL FR BL BR, FL FR FC.
     */
    private void downmix(float[] frame, int channels) {
        float center = 0;
        float lfe = 0;
        float rearLeft = 0;
        float rearRight = 0;

        switch (channels) {
            case 3:
                center = frame[2];
                break;
            case 4:
                rearLeft = frame[2];
                rearRight = frame[3];
                break;
            case 5:
                center = frame[2];
                rearLeft = frame[3];
                rearRight = frame[4];
                break;
            case 6:
                center = frame[2];
                lfe = frame[3];
                rearLeft = frame[4];
                rearRight = frame[5];
                break;
            case 7:
                center = frame[2];
                lfe = frame[3];
                rearLeft = frame[5] + frame[4] * 0.7071f;
                rearRight = frame[6] + frame[4] * 0.7071f;
                break;
            default:
                center = frame[2];
                lfe = frame[3];
                rearLeft = (frame[4] + frame[6]) * 0.7071f;
                rearRight = (frame[5] + frame[7]) * 0.7071f;
                break;
        }

        // The rear channels: what was said a moment ago
        float delayedLeft = rearLeft;
        float delayedRight = rearRight;

        if (mRearLeft.length > 0) {
            delayedLeft = mRearLeft[mRearPos];
            delayedRight = mRearRight[mRearPos];
            mRearLeft[mRearPos] = rearLeft;
            mRearRight[mRearPos] = rearRight;
            mRearPos = (mRearPos + 1) % mRearLeft.length;
        }

        // Speech lives in the center channel: the dialogue boost starts here
        float common = center * (mDialogue ? CENTER_MIX_DIALOGUE : CENTER_MIX) + lfe * LFE_MIX;

        frame[0] = (frame[0] + common + delayedLeft * REAR_MIX - delayedRight * REAR_CROSS) * DOWNMIX_TRIM;
        frame[1] = (frame[1] + common + delayedRight * REAR_MIX - delayedLeft * REAR_CROSS) * DOWNMIX_TRIM;
    }

    @Override
    protected void onFlush() {
        mReductionDb = 0;
        java.util.Arrays.fill(mSpeechState, 0);
        java.util.Arrays.fill(mRumbleState, 0);
        java.util.Arrays.fill(mBassState, 0);
        java.util.Arrays.fill(mSubsonicState, 0);
        java.util.Arrays.fill(mRearLeft, 0);
        java.util.Arrays.fill(mRearRight, 0);
    }

    private void applyOptions() {
        mOptionsVersion = sOptionsVersion;
        mLevelling = sLevelling;
        mDialogue = sIsDialogueBoostEnabled;
        mWide = sIsWideStereoEnabled;
        mBass = sBassBoost;

        int channels = Math.max(channelCount, 1);
        float sampleRate = Math.max(sampleRateHz, 8_000);

        if (mFrame.length != channels) {
            mFrame = new float[channels];
            mSpeechState = new float[channels * 2];
            mRumbleState = new float[channels * 2];
            mBassState = new float[channels * 2];
            mSubsonicState = new float[channels * 2];
        }

        int rearDelay = mDownmix ? Math.round(REAR_DELAY_SEC * sampleRate) : 0;

        if (mRearLeft.length != rearDelay) {
            mRearLeft = new float[rearDelay];
            mRearRight = new float[rearDelay];
            mRearPos = 0;
        }

        if (mLevelling == LEVELLING_NIGHT) {
            setCompressor(-42, 12, 12, 0.003f, 0.3f, sampleRate);
        } else if (mLevelling == LEVELLING_ON) {
            setCompressor(-24, 20, 4, 0.01f, 0.25f, sampleRate);
        } else {
            // A limiter for the bass boost: only the peaks that would clip are touched
            setCompressor(-1.5f, 3, 20, 0.001f, 0.12f, sampleRate);
            mMakeupDb = 0;
        }

        float bassDb = mBass == BASS_STRONG ? BASS_STRONG_DB : BASS_LIGHT_DB;
        setLowShelf(mBassShelf, BASS_HZ, bassDb, sampleRate);
        setHighPass(mSubsonic, SUBSONIC_HZ, 0.7071f, sampleRate);
        // A third of the lift is taken back from the whole: the mix stays about as loud, the limiter has little to do
        mBassTrim = (float) Math.exp(-bassDb / 3 * LN10_DIV_20);

        setPeaking(mSpeech, SPEECH_HZ, SPEECH_Q, SPEECH_LIFT_DB, sampleRate);
        setLowShelf(mRumble, RUMBLE_HZ, RUMBLE_CUT_DB, sampleRate);
    }

    private void setCompressor(float thresholdDb, float kneeDb, float ratio, float attackSec, float releaseSec, float sampleRate) {
        mThresholdDb = thresholdDb;
        mKneeDb = kneeDb;
        mRatio = ratio;
        mAttackCoeff = (float) Math.exp(-1 / (attackSec * sampleRate));
        mReleaseCoeff = (float) Math.exp(-1 / (releaseSec * sampleRate));
        // Brings back a part of what is taken from the loudest signal (the same rule as in the web browsers)
        mMakeupDb = -0.6f * getReductionDb(0);
    }

    /**
     * @return how much the compressor takes from a signal of this level (zero or below)
     */
    private float getReductionDb(float levelDb) {
        float over = levelDb - mThresholdDb;
        float slope = 1 / mRatio - 1;

        if (over <= -mKneeDb / 2) {
            return 0;
        } else if (over >= mKneeDb / 2) {
            return over * slope;
        }

        float inKnee = over + mKneeDb / 2;
        return slope * inKnee * inKnee / (2 * mKneeDb);
    }

    private float nextGain(float peak) {
        float levelDb = (float) Math.log(Math.max(peak, MIN_LEVEL)) / LN10_DIV_20;
        float target = getReductionDb(levelDb);
        // More reduction is needed: fast (attack). Less: slow (release).
        float coeff = target < mReductionDb ? mAttackCoeff : mReleaseCoeff;
        mReductionDb = coeff * mReductionDb + (1 - coeff) * target;
        return (float) Math.exp((mReductionDb + mMakeupDb) * LN10_DIV_20);
    }

    private static float filter(float[] coeffs, float[] state, int channel, float input) {
        int idx = channel * 2;
        float output = coeffs[0] * input + state[idx];
        state[idx] = coeffs[1] * input - coeffs[3] * output + state[idx + 1];
        state[idx + 1] = coeffs[2] * input - coeffs[4] * output;
        return output;
    }

    // The formulas: "Cookbook formulae for audio EQ biquad filter coefficients" by Robert Bristow-Johnson

    private static void setPeaking(float[] coeffs, float frequency, float q, float gainDb, float sampleRate) {
        double a = Math.pow(10, gainDb / 40);
        double w0 = 2 * Math.PI * Math.min(frequency, sampleRate * 0.45f) / sampleRate;
        double alpha = Math.sin(w0) / (2 * q);
        double cos = Math.cos(w0);
        set(coeffs, 1 + alpha * a, -2 * cos, 1 - alpha * a, 1 + alpha / a, -2 * cos, 1 - alpha / a);
    }

    private static void setLowShelf(float[] coeffs, float frequency, float gainDb, float sampleRate) {
        double a = Math.pow(10, gainDb / 40);
        double w0 = 2 * Math.PI * frequency / sampleRate;
        double alpha = Math.sin(w0) / 2 * Math.sqrt(2);
        double cos = Math.cos(w0);
        double beta = 2 * Math.sqrt(a) * alpha;
        set(coeffs,
                a * ((a + 1) - (a - 1) * cos + beta),
                2 * a * ((a - 1) - (a + 1) * cos),
                a * ((a + 1) - (a - 1) * cos - beta),
                (a + 1) + (a - 1) * cos + beta,
                -2 * ((a - 1) + (a + 1) * cos),
                (a + 1) + (a - 1) * cos - beta);
    }

    private static void setHighPass(float[] coeffs, float frequency, float q, float sampleRate) {
        double w0 = 2 * Math.PI * frequency / sampleRate;
        double alpha = Math.sin(w0) / (2 * q);
        double cos = Math.cos(w0);
        set(coeffs, (1 + cos) / 2, -(1 + cos), (1 + cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
    }

    private static void set(float[] coeffs, double b0, double b1, double b2, double a0, double a1, double a2) {
        coeffs[0] = (float) (b0 / a0);
        coeffs[1] = (float) (b1 / a0);
        coeffs[2] = (float) (b2 / a0);
        coeffs[3] = (float) (a1 / a0);
        coeffs[4] = (float) (a2 / a0);
    }
}
