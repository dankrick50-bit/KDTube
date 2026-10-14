package com.liskovsoft.smartyoutubetv2.common.misc.download;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Where the downloads go. Kept as one string (AppPrefs, of the device):<br/>
 * <b>""</b>: Download/KDTube of this device (the default).<br/>
 * <b>"ms:&lt;volume&gt;:&lt;base&gt;"</b> (Android 10+): through the media store, &lt;base&gt;/KDTube on a storage volume: this
 * device, a USB drive, an SD card. &lt;base&gt; is Download or Movies (audio only goes to Music then). No permission is needed,
 * and it works on TVs, which mostly have no folder picker.<br/>
 * <b>"content://..."</b>: a folder chosen with the folder picker of the system (where the device has one).<br/>
 * <b>"/path"</b> (Android 9 and older): the folder of the app on a USB drive or an SD card: the only place there an app
 * may write on such a volume.<br/>
 * <br/>
 * NOTE: a finished download keeps the address it was written to: changing the folder doesn't lose the old ones.
 */
public class DownloadFolder {
    private static final String TAG = DownloadFolder.class.getSimpleName();
    public static final String BASE_DOWNLOAD = "Download";
    public static final String BASE_MOVIES = "Movies";
    static final String MEDIA_STORE_PREFIX = "ms:";
    static final String FOLDER_NAME = DownloadTarget.FOLDER_NAME;
    private static final int REQUEST_PICK_FOLDER = 4271;
    public final String value;
    public final String label;

    private DownloadFolder(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public static String get(Context context) {
        return AppPrefs.instance(context).getDownloadFolder();
    }

    /**
     * What the folder is called, for the settings and the download dialog
     */
    public static String getLabel(Context context) {
        String value = get(context);

        if (value.startsWith("content://")) {
            return getTreeLabel(context, Uri.parse(value));
        }

        for (DownloadFolder choice : getChoices(context)) {
            if (choice.value.equals(value)) {
                return choice.label;
            }
        }

        return value.isEmpty() ? getDefaultLabel(context) : value;
    }

    /**
     * The folders a device offers without a picker
     */
    public static List<DownloadFolder> getChoices(Context context) {
        List<DownloadFolder> result = new ArrayList<>();
        result.add(new DownloadFolder("", getDefaultLabel(context)));

        if (Build.VERSION.SDK_INT >= 29) {
            addMediaStoreChoices(context, result);
        } else {
            addAppFolderChoices(context, result);
        }

        return result;
    }

    private static String getDefaultLabel(Context context) {
        return context.getString(R.string.download_folder_device) + " › " + Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER_NAME;
    }

    @SuppressLint("NewApi")
    private static void addMediaStoreChoices(Context context, List<DownloadFolder> result) {
        Set<String> volumes;

        try {
            volumes = MediaStore.getExternalVolumeNames(context);
        } catch (RuntimeException e) {
            return;
        }

        // The storage of the device first, the removable ones after it
        List<String> ordered = new ArrayList<>();
        for (String volume : volumes) {
            if (MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume)) {
                ordered.add(0, volume);
            } else {
                ordered.add(volume);
            }
        }

        for (String volume : ordered) {
            boolean isPrimary = MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume);
            String name = isPrimary ? context.getString(R.string.download_folder_device) : getVolumeName(context, volume);

            for (String base : new String[] {BASE_DOWNLOAD, BASE_MOVIES}) {
                // This device, Download/KDTube: the default ("")
                if (isPrimary && BASE_DOWNLOAD.equals(base)) {
                    continue;
                }

                result.add(new DownloadFolder(MEDIA_STORE_PREFIX + volume + ":" + base, name + " › " + base + "/" + FOLDER_NAME));
            }
        }
    }

    private static String getVolumeName(Context context, String volume) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                StorageManager manager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);

                for (StorageVolume storageVolume : manager.getStorageVolumes()) {
                    if (volume.equals(storageVolume.getMediaStoreVolumeName())) {
                        return storageVolume.getDescription(context);
                    }
                }
            } catch (RuntimeException e) {
                // the name of the volume then
            }
        }

        return context.getString(R.string.download_folder_removable) + " " + volume;
    }

    /**
     * Android 9 and older: the folder of the app on each removable volume
     */
    private static void addAppFolderChoices(Context context, List<DownloadFolder> result) {
        File[] dirs;

        try {
            dirs = context.getExternalFilesDirs(null);
        } catch (RuntimeException e) {
            return;
        }

        // The first one is on the device itself
        for (int i = 1; dirs != null && i < dirs.length; i++) {
            if (dirs[i] == null) {
                continue;
            }

            File dir = new File(dirs[i], "Downloads");
            result.add(new DownloadFolder(dir.getAbsolutePath(), context.getString(R.string.download_folder_removable) + " " + i +
                    " › " + context.getString(R.string.download_folder_app)));
        }
    }

    /**
     * The folder picker of the system (phones, tablets; TVs mostly have none)
     */
    public static boolean hasFolderPicker(Context context) {
        return Build.VERSION.SDK_INT >= 21 && createPickerIntent().resolveActivity(context.getPackageManager()) != null;
    }

    private static Intent createPickerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    private static void pickFolder(Context context) {
        if (!(context instanceof MotherActivity)) {
            MessageHelpers.showLongMessage(context, R.string.download_folder_no_picker);
            return;
        }

        MotherActivity activity = (MotherActivity) context;
        activity.addOnResult((requestCode, resultCode, data) -> {
            if (requestCode != REQUEST_PICK_FOLDER || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                return;
            }

            Uri tree = data.getData();

            try {
                // Kept after a restart of the device
                activity.getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (RuntimeException e) {
                Log.e(TAG, "No lasting access to the folder: %s", e.getMessage());
                MessageHelpers.showLongMessage(activity, R.string.download_folder_no_picker);
                return;
            }

            set(activity, tree.toString());
        });

        try {
            activity.startActivityForResult(createPickerIntent(), REQUEST_PICK_FOLDER);
        } catch (RuntimeException e) { // ActivityNotFoundException, SecurityException
            MessageHelpers.showLongMessage(context, R.string.download_folder_no_picker);
        }
    }

    private static void set(Context context, String value) {
        AppPrefs.instance(context).setDownloadFolder(value);
        MessageHelpers.showMessage(context, context.getString(R.string.download_folder_set, getLabel(context)));
    }

    /**
     * "primary:Movies/KDTube" -> "Movies/KDTube", "1234-ABCD:Videos" -> "1234-ABCD › Videos"
     */
    private static String getTreeLabel(Context context, Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int colon = id.indexOf(':');

            if (colon < 0) {
                return id;
            }

            String volume = id.substring(0, colon);
            String path = id.substring(colon + 1);
            String name = "primary".equals(volume) ? context.getString(R.string.download_folder_device) : volume;
            return path.isEmpty() ? name : name + " › " + path;
        } catch (RuntimeException e) {
            return tree.toString();
        }
    }

    /**
     * Settings &gt; General &gt; Download folder (and the download dialog)
     *
     * @param onChanged after a choice (null: nothing)
     */
    public static void showDialog(Context context, @Nullable Runnable onChanged) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        String current = get(context);
        List<OptionItem> options = new ArrayList<>();

        for (DownloadFolder choice : getChoices(context)) {
            options.add(UiOptionItem.from(choice.label, option -> {
                set(context, choice.value);
                if (onChanged != null) {
                    dialog.closeDialog();
                    onChanged.run();
                }
            }, choice.value.equals(current)));
        }

        // The folder chosen with the picker before
        if (current.startsWith("content://")) {
            options.add(UiOptionItem.from(getTreeLabel(context, Uri.parse(current)), option -> { }, true));
        }

        // The last line of the same list: a dialog with one list shows its lines right away
        if (hasFolderPicker(context)) {
            options.add(UiOptionItem.from(context.getString(R.string.download_folder_other), option -> {
                dialog.closeDialog();
                pickFolder(context);
            }, false));
        }

        String title = context.getString(R.string.download_folder);
        dialog.appendRadioCategory(title, options);
        dialog.showDialog(title);
    }
}
