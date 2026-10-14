package com.liskovsoft.smartyoutubetv2.common.misc.cast;

import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * The dialog behind the cast button of the player
 */
public class CastDialogUtil {
    /**
     * @param onStarted called when the video has been started on the other device (to pause it here, e.g.)
     */
    public static void showCastDialog(Context context, Video video, int positionSec, @Nullable Runnable onStarted) {
        if (video == null || video.videoId == null) {
            return;
        }

        CastManager manager = CastManager.instance(context);

        MessageHelpers.showMessage(context, R.string.cast_searching);

        manager.findDevices(devices -> {
            AppDialogPresenter dialog = AppDialogPresenter.instance(context);
            List<OptionItem> items = new ArrayList<>();

            for (CastDevice device : devices) {
                items.add(UiOptionItem.from(device.getTitle(), optionItem -> {
                    dialog.closeDialog();
                    play(context, device, video.videoId, positionSec, onStarted);
                }));
            }

            // NOTE: if nothing is found this is the only item. A dialog with a single item isn't shown:
            // the item is clicked right away. That's what is needed here.
            items.add(UiOptionItem.from(context.getString(R.string.cast_link_with_code), optionItem -> {
                dialog.closeDialog();
                showCodeDialog(context, video.videoId, positionSec, onStarted);
            }));

            dialog.appendStringsCategory(context.getString(R.string.cast_to_device), items);
            dialog.showDialog(context.getString(R.string.cast_to_device));
        });
    }

    private static void showCodeDialog(Context context, String videoId, int positionSec, @Nullable Runnable onStarted) {
        SimpleEditDialog.show(context, context.getString(R.string.cast_enter_code), context.getString(R.string.cast_code_hint), "", code -> {
            if (code == null || code.trim().isEmpty()) {
                return false;
            }

            CastManager.instance(context).findDevice(code, (device, error) -> {
                if (device != null) {
                    play(context, device, videoId, positionSec, onStarted);
                } else {
                    MessageHelpers.showLongMessage(context, error != null ?
                            context.getString(R.string.cast_failed, error) : context.getString(R.string.cast_wrong_code));
                }
            });

            return true;
        });
    }

    private static void play(Context context, CastDevice device, String videoId, int positionSec, @Nullable Runnable onStarted) {
        MessageHelpers.showMessage(context, context.getString(R.string.cast_connecting, device.getTitle()));

        CastManager.instance(context).play(device, videoId, positionSec, error -> {
            if (error != null) {
                MessageHelpers.showLongMessage(context, context.getString(R.string.cast_failed, error));
                return;
            }

            MessageHelpers.showLongMessage(context, context.getString(R.string.cast_playing_on, device.getTitle()));

            if (onStarted != null) {
                onStarted.run();
            }
        });
    }
}
