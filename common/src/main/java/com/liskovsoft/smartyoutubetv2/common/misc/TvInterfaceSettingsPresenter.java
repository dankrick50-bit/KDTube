package com.liskovsoft.smartyoutubetv2.common.misc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;

/**
 * Settings &gt; "YouTube TV interface": the interface of the PC edition as an option on Android and Fire OS.<br/>
 * The screen itself lives in the app module (TvInterfaceActivity).
 */
public class TvInterfaceSettingsPresenter {
    private static final String TAG = TvInterfaceSettingsPresenter.class.getSimpleName();
    private static final String ACTIVITY_CLASS_NAME = "com.liskovsoft.smartyoutubetv2.tv.ui.webtv.TvInterfaceActivity";
    @SuppressLint("StaticFieldLeak")
    private static TvInterfaceSettingsPresenter sInstance;
    private Context mContext;

    private TvInterfaceSettingsPresenter(Context context) {
        mContext = context;
    }

    public static TvInterfaceSettingsPresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new TvInterfaceSettingsPresenter(context);
        }

        sInstance.mContext = context;

        return sInstance;
    }

    /**
     * The web engine can be kept up to date since Android 5
     */
    public static boolean isSupported(Context context) {
        return Build.VERSION.SDK_INT >= 21;
    }

    public static void open(Context context) {
        if (context == null || !isSupported(context)) {
            return;
        }

        Intent intent = new Intent();
        intent.setClassName(context, ACTIVITY_CLASS_NAME);

        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "This build has no YouTube TV interface: %s", e.getMessage());
        }
    }

    public void show() {
        MainUIData data = MainUIData.instance(mContext);
        AppDialogPresenter dialog = AppDialogPresenter.instance(mContext);

        dialog.appendSingleButton(UiOptionItem.from(mContext.getString(R.string.tv_interface_open), optionItem -> {
            dialog.closeDialog();
            open(mContext);
        }));
        dialog.appendSingleSwitch(UiOptionItem.from(mContext.getString(R.string.tv_interface_on_start), mContext.getString(R.string.tv_interface_desc),
                optionItem -> data.setTvInterfaceOnStartEnabled(optionItem.isSelected()), data.isTvInterfaceOnStartEnabled()));
        dialog.showDialog(mContext.getString(R.string.tv_interface));
    }
}
