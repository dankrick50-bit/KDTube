package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.liskovsoft.sharedutils.helpers.DeviceHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.misc.download.DownloadManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * Keeps the app a good neighbour: decides how much memory it may hold and gives it back when the
 * device needs it.<br/>
 * <br/>
 * <b>The budget.</b> The biggest block the app owns is the buffer of the player. Its size follows the
 * amount of the RAM of the device <i>and</i> the state of the system at the moment: when the device
 * runs low the buffer shrinks (and the part that is already played is dropped), so the system doesn't
 * have to push other apps out. When the pressure is gone it grows back.<br/>
 * <b>The storage as the buffer.</b> A file that is on the device already (a download, "Device media")
 * isn't copied into memory ahead of time: a couple of seconds is enough, the rest is read when needed.<br/>
 * <b>In the background.</b> Hidden and idle (nothing plays, nothing downloads), the app leaves by itself
 * as soon as the system says that memory is short. Everything it had is free at once.<br/>
 * <br/>
 * NOTE: Android gives an app no way to move its memory to the storage (swap). Writing the stream of a
 * video through the flash of the device instead of the RAM is possible, but it's ~9 GB of writes per hour
 * of 4K: not something to do to a stick with 8 GB. That's why the budget and not a swap file.
 */
public class MemoryKeeper implements ComponentCallbacks2 {
    private static final String TAG = MemoryKeeper.class.getSimpleName();
    public static final int PRESSURE_NONE = 0;
    public static final int PRESSURE_MODERATE = 1;
    public static final int PRESSURE_HIGH = 2;
    private static final long MB = 1024 * 1024;
    private static final long MIN_BUFFER_BYTES = 32 * MB;
    private static final long MAX_BUFFER_BYTES = 192 * MB;
    /**
     * The amount of RAM is unknown
     */
    private static final long DEFAULT_BUFFER_BYTES = 128 * MB;
    private static final long LOCAL_BUFFER_BYTES = 8 * MB;
    private static final long MODERATE_FLOOR_BYTES = 24 * MB;
    private static final long HIGH_FLOOR_BYTES = 16 * MB;
    /**
     * The player never stops loading while it has less than this ahead, whatever the size is
     */
    private static final long MIN_FORWARD_US = 10_000_000;
    private static final long MIN_FORWARD_HIGH_US = 6_000_000;
    private static final long MIN_FORWARD_LOCAL_US = 3_000_000;
    /**
     * The part that is already played (rewind without loading): 10 seconds per GB of RAM
     */
    private static final long BACK_BUFFER_US_PER_GB = 10_000_000;
    private static final long MAX_BACK_BUFFER_US = 60_000_000;
    /**
     * The state of the system is asked not more often than this (it's a call to another process)
     */
    private static final long PRESSURE_CHECK_MS = 3_000;
    /**
     * How long a warning of the system is remembered
     */
    private static final long TRIM_MEMORY_MS = 30_000;
    private static MemoryKeeper sInstance;
    private final Context mContext;
    private final ActivityManager mActivityManager;
    private final ActivityManager.MemoryInfo mMemoryInfo = new ActivityManager.MemoryInfo();
    private final long mDeviceRam;
    private volatile boolean mIsLocalSource;
    private volatile boolean mIsPlayerActive;
    private volatile int mTrimPressure;
    private volatile long mTrimPressureTimeMs;
    private int mPressure;
    private long mPressureTimeMs = -PRESSURE_CHECK_MS;
    private boolean mIsStarted;

    private MemoryKeeper(Context context) {
        mContext = context.getApplicationContext();
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mDeviceRam = DeviceHelpers.getDeviceRam(mContext);
    }

    public static synchronized MemoryKeeper instance(Context context) {
        if (sInstance == null) {
            sInstance = new MemoryKeeper(context);
        }

        return sInstance;
    }

    /**
     * Starts to listen to the system. Once, from the application.
     */
    public synchronized void start() {
        if (mIsStarted) {
            return;
        }

        mIsStarted = true;
        mContext.registerComponentCallbacks(this);
        Log.d(TAG, "RAM: %s MB, buffer of the player: %s MB", mDeviceRam / MB, getBaseBufferBytes() / MB);
    }

    /**
     * @return how short of memory the device is at the moment: one of the PRESSURE constants
     */
    public synchronized int getPressure() {
        long now = SystemClock.elapsedRealtime();

        if (now - mPressureTimeMs < PRESSURE_CHECK_MS) {
            return mPressure;
        }

        int pressure = now - mTrimPressureTimeMs < TRIM_MEMORY_MS ? mTrimPressure : PRESSURE_NONE;

        if (mActivityManager != null) {
            try {
                mActivityManager.getMemoryInfo(mMemoryInfo);

                if (mMemoryInfo.lowMemory) {
                    pressure = PRESSURE_HIGH;
                } else if (mMemoryInfo.threshold > 0 && mMemoryInfo.availMem < mMemoryInfo.threshold * 3 / 2) {
                    // Close to the point where the system starts to remove other apps
                    pressure = Math.max(pressure, PRESSURE_MODERATE);
                }
            } catch (RuntimeException e) {
                // the system is busy: the last known state stays
            }
        }

        if (pressure != mPressure) {
            Log.d(TAG, "Memory pressure: %s -> %s (available: %s MB)", mPressure, pressure, mMemoryInfo.availMem / MB);
        }

        mPressure = pressure;
        mPressureTimeMs = now;

        return pressure;
    }

    /**
     * @return how much the player may hold in its buffer at the moment
     */
    public long getPlayerBufferBytes() {
        if (mIsLocalSource) {
            return LOCAL_BUFFER_BYTES;
        }

        long base = getBaseBufferBytes();

        switch (getPressure()) {
            case PRESSURE_MODERATE:
                return Math.max(MODERATE_FLOOR_BYTES, base / 2);
            case PRESSURE_HIGH:
                return Math.max(HIGH_FLOOR_BYTES, base / 4);
            default:
                return base;
        }
    }

    /**
     * 1 GB: ~40 MB, 2 GB: ~80 MB, 3 GB: ~120 MB, 4 GB: ~160 MB, more: 192 MB
     */
    private long getBaseBufferBytes() {
        // NOTE: zero or negative means that the amount is unknown
        return mDeviceRam > 0 ? Math.max(MIN_BUFFER_BYTES, Math.min(MAX_BUFFER_BYTES, mDeviceRam / 24)) : DEFAULT_BUFFER_BYTES;
    }

    /**
     * @return how much of the already played part the player may keep
     */
    public long getBackBufferUs() {
        // Reading a local file again costs nothing. And under pressure this is the first thing to give away.
        if (mIsLocalSource || getPressure() != PRESSURE_NONE) {
            return 0;
        }

        if (mDeviceRam <= 0) {
            return MAX_BACK_BUFFER_US;
        }

        long gigabytes = Math.max(1, Math.round(mDeviceRam / 1_000_000_000d));
        return Math.min(MAX_BACK_BUFFER_US, gigabytes * BACK_BUFFER_US_PER_GB);
    }

    /**
     * @return the player loads at least this much ahead, whatever the size is
     */
    public long getMinForwardBufferUs() {
        if (mIsLocalSource) {
            return MIN_FORWARD_LOCAL_US;
        }

        return getPressure() == PRESSURE_HIGH ? MIN_FORWARD_HIGH_US : MIN_FORWARD_US;
    }

    /**
     * @param isLocal the video that is about to play is a file on this device
     */
    public void setLocalSource(boolean isLocal) {
        mIsLocalSource = isLocal;
    }

    /**
     * @param isActive the player is prepared (it plays, or is paused with a video loaded)
     */
    public void setPlayerActive(boolean isActive) {
        mIsPlayerActive = isActive;
    }

    private boolean isIdle() {
        return !mIsPlayerActive && !DownloadManager.instance(mContext).hasActive();
    }

    @Override
    public void onTrimMemory(int level) {
        if (level == TRIM_MEMORY_RUNNING_MODERATE) {
            setTrimPressure(PRESSURE_MODERATE);
        } else if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            setTrimPressure(PRESSURE_HIGH);
        } else if (level == TRIM_MEMORY_UI_HIDDEN) {
            // The app isn't visible anymore. The image caches are emptied by their owner; collect what's left behind.
            System.gc();
        } else if (level >= TRIM_MEMORY_MODERATE) {
            // The app is in the background and the system is about to remove such apps to get memory.
            // Leaving by itself is faster and nothing is lost: the state is saved first.
            if (isIdle()) {
                Log.d(TAG, "In the background, idle, the system is short of memory (level %s): leaving", level);
                Utils.forceFinishTheApp(mContext);
            }
        }
    }

    private void setTrimPressure(int pressure) {
        mTrimPressure = pressure;
        mTrimPressureTimeMs = SystemClock.elapsedRealtime();

        synchronized (this) {
            mPressureTimeMs = -PRESSURE_CHECK_MS; // ask again right away
        }
    }

    @Override
    public void onLowMemory() {
        setTrimPressure(PRESSURE_HIGH);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        // NOP
    }
}
