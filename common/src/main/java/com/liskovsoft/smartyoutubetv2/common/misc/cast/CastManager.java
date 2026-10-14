package com.liskovsoft.smartyoutubetv2.common.misc.cast;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plays a video on another device (see {@link DialDiscovery}, {@link LoungeSender}).<br/>
 * The work is done in the background, the callbacks are called on the main thread.
 */
public class CastManager {
    private static final String TAG = CastManager.class.getSimpleName();
    private static final String DEVICES_FILE_NAME = "cast_devices.json";
    private static final String SENDER_NAME = "KDTube";
    private static final int DISCOVERY_TIMEOUT_MS = 3_000;
    private static final int MAX_SAVED_DEVICES = 6;
    @SuppressLint("StaticFieldLeak")
    private static CastManager sInstance;
    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final List<CastDevice> mSavedDevices = new ArrayList<>();

    public interface DevicesCallback {
        void onDevices(List<CastDevice> devices);
    }

    public interface DeviceCallback {
        /**
         * @param device null if there's no such screen
         */
        void onDevice(@Nullable CastDevice device, @Nullable String error);
    }

    public interface ResultCallback {
        /**
         * @param error null if the video has been started
         */
        void onResult(@Nullable String error);
    }

    private CastManager(Context context) {
        mContext = context.getApplicationContext();
        load();
    }

    public static CastManager instance(Context context) {
        if (sInstance == null) {
            sInstance = new CastManager(context);
        }

        return sInstance;
    }

    /**
     * The devices of the local network first, then the ones that were used before (linked with a code, e.g.)
     */
    public void findDevices(DevicesCallback callback) {
        List<CastDevice> saved = new ArrayList<>(mSavedDevices);

        mExecutor.execute(() -> {
            List<CastDevice> result = DialDiscovery.discover(DISCOVERY_TIMEOUT_MS);

            for (CastDevice device : saved) {
                if (!contains(result, device)) {
                    result.add(device);
                }
            }

            mMainHandler.post(() -> callback.onDevices(result));
        });
    }

    /**
     * Finds the screen by the code it shows ("Link with TV code")
     */
    public void findDevice(String code, DeviceCallback callback) {
        mExecutor.execute(() -> {
            CastDevice device = null;
            String error = null;

            try {
                device = LoungeSender.findScreen(code);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Can't find the screen: %s", e);
                error = getMessage(e);
            }

            CastDevice result = device;
            String resultError = error;
            mMainHandler.post(() -> callback.onDevice(result, resultError));
        });
    }

    public void play(CastDevice device, String videoId, int positionSec, ResultCallback callback) {
        mExecutor.execute(() -> {
            String error = null;

            try {
                // A device of the local network: its app could be closed (or restarted with another id)
                if (device.appUrl != null) {
                    device.screenId = DialDiscovery.startApp(device);
                } else if (device.castHost != null) {
                    device.screenId = CastChannel.getScreenId(device.castHost);
                }

                if (device.screenId == null) {
                    throw new IOException("The device has no YouTube app");
                }

                LoungeSender.play(device.screenId, SENDER_NAME, videoId, positionSec);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Can't play on %s: %s", device.getTitle(), e);
                error = getMessage(e);
            }

            String result = error;

            mMainHandler.post(() -> {
                if (result == null) {
                    remember(device);
                }

                callback.onResult(result);
            });
        });
    }

    public void forget(CastDevice device) {
        Helpers.removeIf(mSavedDevices, saved -> isSame(saved, device));
        save();
    }

    private void remember(CastDevice device) {
        Helpers.removeIf(mSavedDevices, saved -> isSame(saved, device));
        mSavedDevices.add(0, device);

        while (mSavedDevices.size() > MAX_SAVED_DEVICES) {
            mSavedDevices.remove(mSavedDevices.size() - 1);
        }

        save();
    }

    private static boolean contains(List<CastDevice> devices, CastDevice device) {
        for (CastDevice item : devices) {
            if (isSame(item, device)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isSame(CastDevice left, CastDevice right) {
        if (left.appUrl != null && right.appUrl != null) {
            return left.appUrl.equals(right.appUrl);
        }

        if (left.castHost != null && right.castHost != null) {
            return left.castHost.equals(right.castHost);
        }

        if (left.screenId != null && right.screenId != null && left.screenId.equals(right.screenId)) {
            return true;
        }

        // The same TV: found in the network now and linked with a code before
        return left.name != null && left.name.equals(right.name);
    }

    private static String getMessage(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }

    private File getDevicesFile() {
        return new File(mContext.getFilesDir(), DEVICES_FILE_NAME);
    }

    private void load() {
        File file = getDevicesFile();

        if (!file.exists()) {
            return;
        }

        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int length = 0;

            for (int read; length < data.length && (read = input.read(data, length, data.length - length)) > 0; ) {
                length += read;
            }

            JSONArray devices = new JSONArray(new String(data, 0, length, StandardCharsets.UTF_8));

            for (int i = 0; i < devices.length(); i++) {
                JSONObject json = devices.getJSONObject(i);
                CastDevice device = new CastDevice(json.optString("name", null), json.optString("screenId", null), json.optString("appUrl", null));
                device.castHost = json.optString("castHost", null);

                if (device.screenId != null || device.appUrl != null || device.castHost != null) {
                    mSavedDevices.add(device);
                }
            }
        } catch (IOException | JSONException | RuntimeException e) {
            Log.e(TAG, "Can't read the list of the devices: %s", e.getMessage());
        }
    }

    private void save() {
        try {
            JSONArray devices = new JSONArray();

            for (CastDevice device : mSavedDevices) {
                JSONObject json = new JSONObject();
                json.put("name", device.name);
                json.put("screenId", device.screenId);
                json.put("appUrl", device.appUrl);
                json.put("castHost", device.castHost);
                devices.put(json);
            }

            try (Writer writer = new OutputStreamWriter(new FileOutputStream(getDevicesFile()), StandardCharsets.UTF_8)) {
                writer.write(devices.toString());
            }
        } catch (IOException | JSONException | RuntimeException e) {
            Log.e(TAG, "Can't save the list of the devices: %s", e.getMessage());
        }
    }
}
