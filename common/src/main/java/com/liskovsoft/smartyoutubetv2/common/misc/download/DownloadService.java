package com.liskovsoft.smartyoutubetv2.common.misc.download;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * Does nothing by itself: keeps the app alive while {@link DownloadManager} has something to save
 */
public class DownloadService extends Service {
    private static final String TAG = DownloadService.class.getSimpleName();
    private static final int NOTIFICATION_ID = 4512;
    private static boolean sIsRunning;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            startForeground(NOTIFICATION_ID, Utils.createNotification(
                    getApplicationContext(),
                    getApplicationInfo().icon,
                    getString(R.string.download_in_progress),
                    ViewManager.instance(getApplicationContext()).getRootActivity()));
        } catch (Exception e) {
            // ForegroundServiceStartNotAllowedException (Android 12+): the app is in the background already
            Log.e(TAG, "Can't go foreground: %s", e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The process was killed and the system has restarted the service: nothing to continue
        if (!DownloadManager.instance(getApplicationContext()).hasActive()) {
            sIsRunning = false;
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        sIsRunning = false;
        super.onDestroy();
    }

    public static void start(Context context) {
        if (sIsRunning) {
            return;
        }

        try {
            Intent intent = new Intent(context, DownloadService.class);

            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }

            sIsRunning = true;
        } catch (Exception e) {
            // The download continues anyway while the app is alive
            Log.e(TAG, "Can't start the service: %s", e.getMessage());
        }
    }

    public static void stop(Context context) {
        if (!sIsRunning) {
            return;
        }

        sIsRunning = false;

        try {
            context.stopService(new Intent(context, DownloadService.class));
        } catch (Exception e) {
            Log.e(TAG, "Can't stop the service: %s", e.getMessage());
        }
    }
}
