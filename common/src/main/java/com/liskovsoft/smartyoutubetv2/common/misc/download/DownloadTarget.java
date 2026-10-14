package com.liskovsoft.smartyoutubetv2.common.misc.download;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;

import com.liskovsoft.sharedutils.helpers.PermissionHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

/**
 * The file that is being written, in the folder of the settings (see DownloadFolder).<br/>
 * Default: Android 10+: Download/KDTube through the media store (no permission needed).
 * Older: the same folder directly (storage permission). If there's no shared storage: the folder of the app.
 */
public class DownloadTarget {
    private static final String TAG = DownloadTarget.class.getSimpleName();
    public static final String FOLDER_NAME = "KDTube";
    private static final String CONTENT_SCHEME = "content://";
    private final Context mContext;
    private final String mLocation;
    private ParcelFileDescriptor mDescriptor;
    private FileOutputStream mStream;
    private RandomAccessFile mFile;

    private DownloadTarget(Context context, String location) {
        mContext = context.getApplicationContext();
        mLocation = location;
    }

    /**
     * @param name without the extension
     */
    public static DownloadTarget create(Context context, String name, String extension, String mimeType) throws IOException {
        String fileName = sanitize(name) + "." + extension;
        String folder = DownloadFolder.get(context);

        if (folder.startsWith(CONTENT_SCHEME)) {
            return new DownloadTarget(context, createDocument(context, Uri.parse(folder), fileName).toString());
        }

        if (Build.VERSION.SDK_INT >= 29 && folder.startsWith(DownloadFolder.MEDIA_STORE_PREFIX)) {
            String[] parts = folder.substring(DownloadFolder.MEDIA_STORE_PREFIX.length()).split(":");
            String volume = parts[0];
            String base = parts.length > 1 ? parts[1] : DownloadFolder.BASE_DOWNLOAD;
            return new DownloadTarget(context, createMediaStoreItem(context, fileName, mimeType, volume, base).toString());
        }

        if (Build.VERSION.SDK_INT >= 29) {
            return new DownloadTarget(context, createMediaStoreItem(context, fileName, mimeType,
                    MediaStore.VOLUME_EXTERNAL_PRIMARY, DownloadFolder.BASE_DOWNLOAD).toString());
        }

        File dir = folder.startsWith("/") ? getFolderDir(context, folder) : getLegacyDir(context);
        File file = new File(dir, fileName);

        for (int i = 2; file.exists(); i++) {
            file = new File(dir, sanitize(name) + " (" + i + ")." + extension);
        }

        return new DownloadTarget(context, file.getAbsolutePath());
    }

    public static DownloadTarget from(Context context, String location) {
        return new DownloadTarget(context, location);
    }

    /**
     * @return true if the user should grant the access to the storage first (the request is shown)
     */
    public static boolean requestPermissionIfNeeded(Context context) {
        if (!DownloadFolder.get(context).isEmpty()) {
            return false; // the folder of the app, or one chosen with the picker
        }

        if (Build.VERSION.SDK_INT >= 29 || Build.VERSION.SDK_INT < 23 || PermissionHelpers.hasStoragePermissions(context)) {
            return false;
        }

        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            return false; // the folder of the app will be used
        }

        PermissionHelpers.verifyStoragePermissions(context);
        return true;
    }

    /**
     * @return free space where the files are stored, -1 if unknown
     */
    public static long getFreeBytes(Context context) {
        String folder = DownloadFolder.get(context);

        if (folder.startsWith(CONTENT_SCHEME) || folder.startsWith(DownloadFolder.MEDIA_STORE_PREFIX)) {
            return -1; // another volume: not known here
        }

        try {
            File dir = folder.startsWith("/") ? getFolderDir(context, folder) :
                    Build.VERSION.SDK_INT >= 29 ? Environment.getExternalStorageDirectory() : getLegacyDir(context);
            return new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * Uri (content://) or absolute path
     */
    public String getLocation() {
        return mLocation;
    }

    public boolean isContent() {
        return mLocation.startsWith(CONTENT_SCHEME);
    }

    public Uri getUri() {
        return toUri(mLocation);
    }

    public static Uri toUri(String location) {
        return location.startsWith(CONTENT_SCHEME) ? Uri.parse(location) : Uri.fromFile(new File(location));
    }

    /**
     * @return writable and seekable. Closed by {@link #commit} or {@link #delete} at the latest.
     */
    public FileChannel openChannel() throws IOException {
        if (!isContent()) {
            mFile = new RandomAccessFile(mLocation, "rw");
            return mFile.getChannel();
        }

        mDescriptor = mContext.getContentResolver().openFileDescriptor(Uri.parse(mLocation), "rw");

        if (mDescriptor == null) {
            throw new IOException("Can't open " + mLocation);
        }

        mStream = new FileOutputStream(mDescriptor.getFileDescriptor());
        return mStream.getChannel();
    }

    public MediaMuxer createMuxer(int outputFormat) throws IOException {
        if (!isContent()) {
            return new MediaMuxer(mLocation, outputFormat);
        }

        if (Build.VERSION.SDK_INT < 26) {
            throw new IOException("Not supported"); // can't happen: content is for Android 10+
        }

        mDescriptor = mContext.getContentResolver().openFileDescriptor(Uri.parse(mLocation), "rw");

        if (mDescriptor == null) {
            throw new IOException("Can't open " + mLocation);
        }

        return new MediaMuxer(mDescriptor.getFileDescriptor(), outputFormat);
    }

    /**
     * The file is complete: make it visible for the other apps
     */
    public void commit() {
        closeDescriptor();

        if (isContent() && !isDocument() && Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);

            try {
                mContext.getContentResolver().update(Uri.parse(mLocation), values, null, null);
            } catch (RuntimeException e) {
                Log.e(TAG, "Can't publish the file: %s", e.getMessage());
            }
        }
    }

    public void delete() {
        closeDescriptor();

        try {
            if (isDocument()) {
                DocumentsContract.deleteDocument(mContext.getContentResolver(), Uri.parse(mLocation));
            } else if (isContent()) {
                mContext.getContentResolver().delete(Uri.parse(mLocation), null, null);
            } else {
                //noinspection ResultOfMethodCallIgnored
                new File(mLocation).delete();
            }
        } catch (FileNotFoundException | RuntimeException e) {
            Log.e(TAG, "Can't delete the file: %s", e.getMessage());
        }
    }

    public boolean exists() {
        if (!isContent()) {
            return new File(mLocation).exists();
        }

        String column = isDocument() ? DocumentsContract.Document.COLUMN_DOCUMENT_ID : MediaStore.MediaColumns._ID;

        try (Cursor cursor = mContext.getContentResolver().query(Uri.parse(mLocation), new String[]{column}, null, null, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public long length() {
        if (!isContent()) {
            return new File(mLocation).length();
        }

        String column = isDocument() ? DocumentsContract.Document.COLUMN_SIZE : MediaStore.MediaColumns.SIZE;

        try (Cursor cursor = mContext.getContentResolver().query(Uri.parse(mLocation), new String[]{column}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getLong(0) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void closeDescriptor() {
        for (Closeable closeable : new Closeable[] {mStream, mFile}) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (IOException e) {
                    // Nothing to do
                }
            }
        }

        mStream = null;
        mFile = null;

        if (mDescriptor != null) {
            try {
                mDescriptor.close();
            } catch (IOException e) {
                // Nothing to do
            }

            mDescriptor = null;
        }
    }

    /**
     * @param volume of the media store: the device (external_primary), a USB drive, an SD card
     * @param base Download, or Movies (audio only goes to Music then: the media store keeps the kinds apart)
     */
    @RequiresApi(29)
    private static Uri createMediaStoreItem(Context context, String fileName, String mimeType, String volume, String base) throws IOException {
        boolean isAudio = mimeType != null && mimeType.startsWith("audio/");
        Uri collection;
        String dir;

        if (DownloadFolder.BASE_MOVIES.equals(base)) {
            collection = isAudio ? MediaStore.Audio.Media.getContentUri(volume) : MediaStore.Video.Media.getContentUri(volume);
            dir = isAudio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES;
        } else {
            collection = MediaStore.Downloads.getContentUri(volume);
            dir = Environment.DIRECTORY_DOWNLOADS;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/" + FOLDER_NAME);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri;

        try {
            uri = context.getContentResolver().insert(collection, values);
        } catch (RuntimeException e) {
            throw new IOException("Can't create a file in the Downloads folder", e);
        }

        if (uri == null) {
            throw new IOException("Can't create a file in the Downloads folder");
        }

        return uri;
    }

    /**
     * A file in a folder chosen with the folder picker. NOTE: "application/octet-stream": with the real type the picker
     * adds an extension of its choice to the name (".mp4.m4a").
     */
    private static Uri createDocument(Context context, Uri tree, String fileName) throws IOException {
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            Uri uri = DocumentsContract.createDocument(context.getContentResolver(), parent, "application/octet-stream", fileName);

            if (uri != null) {
                return uri;
            }
        } catch (FileNotFoundException | RuntimeException e) {
            Log.e(TAG, "Can't create the file in the chosen folder: %s", e.getMessage());
        }

        // The folder is gone, or the access to it
        throw new IOException(context.getString(R.string.download_folder_lost));
    }

    /**
     * The folder of the app on a removable volume (Android 9 and older)
     */
    private static File getFolderDir(Context context, String path) throws IOException {
        File dir = new File(path);

        if ((!dir.isDirectory() && !dir.mkdirs()) || !dir.canWrite()) {
            throw new IOException(context.getString(R.string.download_folder_lost));
        }

        return dir;
    }

    /**
     * A file of the folder picker (not of the media store)
     */
    private boolean isDocument() {
        return isContent() && Build.VERSION.SDK_INT >= 19 && DocumentsContract.isDocumentUri(mContext, Uri.parse(mLocation));
    }

    private static File getLegacyDir(Context context) throws IOException {
        File dir = null;

        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) &&
                (Build.VERSION.SDK_INT < 23 || PermissionHelpers.hasStoragePermissions(context))) {
            dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER_NAME);

            if (!dir.isDirectory() && !dir.mkdirs()) {
                dir = null;
            }
        }

        if (dir == null) {
            // No shared storage (or no access to it)
            File base = context.getExternalFilesDir(null);
            dir = new File(base != null ? base : context.getFilesDir(), "Downloads");
        }

        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Can't create the folder " + dir);
        }

        return dir;
    }

    private static String sanitize(String name) {
        String result = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();

        if (result.length() > 100) {
            result = result.substring(0, 100).trim();
        }

        return result.isEmpty() ? "video" : result;
    }
}
