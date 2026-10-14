package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.liskovsoft.sharedutils.mylogger.Log;

/**
 * What the sound of the device goes to at the moment, as far as the choice of the audio track cares:
 * something with more than two speakers (a receiver, a sound bar: over HDMI or USB), or two channels
 * (the speakers of a tablet or a TV, headphones, Bluetooth).
 */
public final class AudioOutputInfo {
    private static final String TAG = AudioOutputInfo.class.getSimpleName();

    private AudioOutputInfo() {
    }

    /**
     * @return the output takes surround sound: as 5.1 / 7.1 channels, or undecoded (Dolby, DTS) to decode it itself
     */
    public static boolean isSurroundOutput(Context context) {
        if (context == null) {
            return false;
        }

        try {
            // What the HDMI output says it can decode itself (also the setting of the device that forces it)
            AudioCapabilities capabilities = AudioCapabilities.getCapabilities(context);

            if (capabilities.supportsEncoding(C.ENCODING_AC3) || capabilities.supportsEncoding(C.ENCODING_E_AC3) ||
                    capabilities.supportsEncoding(C.ENCODING_DTS)) {
                return true;
            }

            if (Build.VERSION.SDK_INT >= 23) {
                AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

                if (manager != null) {
                    for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                        if (isExternal(device.getType()) && (hasManyChannels(device) || hasSurroundEncoding(device))) {
                            return true;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Can't ask the device about its audio output: %s", e.getMessage());
        }

        return false;
    }

    private static boolean isExternal(int type) {
        return type == AudioDeviceInfo.TYPE_HDMI || type == AudioDeviceInfo.TYPE_HDMI_ARC || type == 29 /* TYPE_HDMI_EARC */ ||
                type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_LINE_DIGITAL;
    }

    private static boolean hasManyChannels(AudioDeviceInfo device) {
        for (int count : device.getChannelCounts()) {
            if (count > 2) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasSurroundEncoding(AudioDeviceInfo device) {
        for (int encoding : device.getEncodings()) {
            if (encoding == AudioFormat.ENCODING_AC3 || encoding == AudioFormat.ENCODING_E_AC3 || encoding == AudioFormat.ENCODING_DTS ||
                    encoding == AudioFormat.ENCODING_DTS_HD || encoding == 18 /* ENCODING_E_AC3_JOC */ || encoding == AudioFormat.ENCODING_DOLBY_TRUEHD) {
                return true;
            }
        }

        return false;
    }

    /**
     * @return the device brings its own decoder for the format (the one of its maker, not the generic software ones).
     * For Dolby sound that means the processing of the maker too: Dolby Atmos on the speakers and headphones of a Fire tablet, e.g.
     */
    public static boolean hasDeviceDecoder(String mimeType) {
        if (mimeType == null || Build.VERSION.SDK_INT < 21) {
            return false;
        }

        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }

                String name = info.getName().toLowerCase();

                if (name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains("ffmpeg")) {
                    continue;
                }

                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mimeType)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            // the list of the codecs can't be read
        }

        return false;
    }
}
