package com.liskovsoft.smartyoutubetv2.common.misc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Finds the video and audio files of the device: the internal storage, memory cards and USB drives.<br/>
 * <br/>
 * Two sources, merged: the media index of the system (fast, has the durations) and a walk
 * through the folders (finds what isn't indexed yet, a drive that has just been plugged in e.g.).<br/>
 * <br/>
 * The list is cached: {@link #getVideos()} never blocks, a new scan runs in the background
 * and the listeners are told when the list has changed.
 */
public class LocalMediaScanner {
    private static final String TAG = LocalMediaScanner.class.getSimpleName();
    /**
     * Id of the section of the main screen (see BrowsePresenter).<br/>
     * NOTE: the sections are compared by Video.hashCode(), that is |id - 31|: check the other ids before adding one.
     */
    public static final int SECTION_ID = 71;
    private static final int PERMISSION_REQUEST_CODE = 7_351;
    private static final int MAX_DEPTH = 7;
    private static final int MAX_FILES = 3_000;
    private static final long MIN_SIZE_BYTES = 200 * 1024;
    private static final long RESCAN_INTERVAL_MS = 30_000;
    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "webm", "m4v", "mov", "avi", "ts", "m2ts", "mpg", "mpeg", "flv", "3gp", "wmv"));
    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "wma", "mka"));
    private static final Set<String> SKIPPED_FOLDERS = new HashSet<>(Arrays.asList(
            "android", "lost.dir", "system volume information", "$recycle.bin", "dcim/.thumbnails", "notifications", "ringtones", "alarms"));
    @SuppressLint("StaticFieldLeak")
    private static LocalMediaScanner sInstance;
    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final List<Runnable> mListeners = new CopyOnWriteArrayList<>();
    private volatile List<Video> mVideos = Collections.emptyList();
    private boolean mIsScanning;
    private long mLastScanMs;

    private static final class Entry {
        String title;
        String location; // uri or path
        String folder;
        long sizeBytes;
        long durationMs;
        long modifiedSec;
        boolean isAudio;
    }

    private LocalMediaScanner(Context context) {
        mContext = context.getApplicationContext();
    }

    public static LocalMediaScanner instance(Context context) {
        if (sInstance == null) {
            sInstance = new LocalMediaScanner(context);
        }

        return sInstance;
    }

    /**
     * Called (on the main thread) when the list has changed
     */
    public void addListener(Runnable listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    /**
     * @return what is known at the moment: videos first, newest first. Starts a new scan if the list is old.
     */
    public List<Video> getVideos() {
        scan(false);
        return mVideos;
    }

    public void scan(boolean force) {
        long nowMs = System.currentTimeMillis();

        if (mIsScanning || (!force && nowMs - mLastScanMs < RESCAN_INTERVAL_MS) || !hasPermission(mContext)) {
            return;
        }

        mIsScanning = true;
        mLastScanMs = nowMs;

        Thread thread = new Thread(() -> {
            List<Video> result;

            try {
                result = findAll();
            } catch (RuntimeException e) {
                Log.e(TAG, "Scan error: %s", e);
                result = null;
            }

            List<Video> videos = result;

            mMainHandler.post(() -> {
                mIsScanning = false;

                if (videos != null && !isSame(videos, mVideos)) {
                    mVideos = videos;

                    for (Runnable listener : mListeners) {
                        listener.run();
                    }
                }
            });
        }, "LocalMediaScanner");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    public static boolean hasPermission(Context context) {
        for (String permission : getPermissions()) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        return true;
    }

    /**
     * @return true if the user is being asked for the access (the list will be empty till then)
     */
    public static boolean requestPermissionIfNeeded(Context context) {
        if (Build.VERSION.SDK_INT < 23 || hasPermission(context) || !(context instanceof Activity)) {
            return false;
        }

        ActivityCompat.requestPermissions((Activity) context, getPermissions(), PERMISSION_REQUEST_CODE);
        return true;
    }

    private static String[] getPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[] {Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO};
        }

        return new String[] {Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private List<Video> findAll() {
        // Path (or uri, when the path isn't known) -> file. The index goes first: it knows more.
        Map<String, Entry> entries = new LinkedHashMap<>();

        queryIndex(entries, false);
        queryIndex(entries, true);

        for (File root : getRoots()) {
            walk(root, 0, entries);
        }

        List<Entry> sorted = new ArrayList<>(entries.values());
        Collections.sort(sorted, (left, right) -> left.isAudio != right.isAudio ? (left.isAudio ? 1 : -1) :
                Long.compare(right.modifiedSec, left.modifiedSec));

        List<Video> result = new ArrayList<>();

        for (Entry entry : sorted) {
            result.add(toVideo(entry));
        }

        return result;
    }

    private void queryIndex(Map<String, Entry> entries, boolean audio) {
        Uri base = audio ?
                (Build.VERSION.SDK_INT >= 29 ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) :
                (Build.VERSION.SDK_INT >= 29 ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) : MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        String[] projection = {
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.TITLE, MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, "duration"};

        try (Cursor cursor = mContext.getContentResolver().query(base, projection, null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor == null) {
                return;
            }

            while (cursor.moveToNext() && entries.size() < MAX_FILES) {
                String path = cursor.getString(3);
                String name = cursor.getString(1) != null ? cursor.getString(1) : cursor.getString(2);
                long size = cursor.getLong(4);

                if (name == null || size < MIN_SIZE_BYTES || (audio && path != null && isSkipped(path))) {
                    continue;
                }

                Entry entry = new Entry();
                entry.title = stripExtension(name);
                entry.location = ContentUris.withAppendedId(base, cursor.getLong(0)).toString();
                entry.folder = path != null && new File(path).getParentFile() != null ? new File(path).getParentFile().getName() : null;
                entry.sizeBytes = size;
                entry.modifiedSec = cursor.getLong(5);
                entry.durationMs = cursor.getLong(6);
                entry.isAudio = audio;

                // Ringtones and the like
                if (audio && entry.durationMs > 0 && entry.durationMs < 30_000) {
                    continue;
                }

                entries.put(path != null ? path : entry.location, entry);
            }
        } catch (RuntimeException e) {
            // No access, a volume that has just been removed etc
            Log.e(TAG, "Can't read the media index: %s", e.getMessage());
        }
    }

    /**
     * The top folders of all the storages: internal, memory cards, USB drives
     */
    private List<File> getRoots() {
        Set<String> paths = new HashSet<>();
        List<File> result = new ArrayList<>();

        List<File> candidates = new ArrayList<>();
        candidates.add(Environment.getExternalStorageDirectory());

        // Every volume has a folder of the app: .../Android/data/<package>/files. The volume is 4 levels up.
        File[] appDirs = ContextCompat.getExternalFilesDirs(mContext, null);

        for (File dir : appDirs) {
            File root = dir;

            for (int i = 0; i < 4 && root != null; i++) {
                root = root.getParentFile();
            }

            candidates.add(root);
        }

        File[] mounts = new File("/storage").listFiles();

        if (mounts != null) {
            for (File mount : mounts) {
                if (!"emulated".equals(mount.getName()) && !"self".equals(mount.getName())) {
                    candidates.add(mount);
                }
            }
        }

        for (File candidate : candidates) {
            try {
                if (candidate != null && candidate.isDirectory() && candidate.canRead() && paths.add(candidate.getCanonicalPath())) {
                    result.add(candidate);
                }
            } catch (java.io.IOException | SecurityException e) {
                // Not a readable volume
            }
        }

        return result;
    }

    private void walk(File dir, int depth, Map<String, Entry> entries) {
        if (depth > MAX_DEPTH || entries.size() >= MAX_FILES) {
            return;
        }

        File[] files;

        try {
            files = dir.listFiles();
        } catch (SecurityException e) {
            return;
        }

        if (files == null) {
            return;
        }

        for (File file : files) {
            String name = file.getName();

            if (name.startsWith(".")) {
                continue;
            }

            if (file.isDirectory()) {
                if (!SKIPPED_FOLDERS.contains(name.toLowerCase(Locale.US))) {
                    walk(file, depth + 1, entries);
                }
                continue;
            }

            String extension = getExtension(name);
            boolean isVideo = VIDEO_EXTENSIONS.contains(extension);
            boolean isAudio = AUDIO_EXTENSIONS.contains(extension);

            if ((!isVideo && !isAudio) || entries.containsKey(file.getAbsolutePath()) || file.length() < MIN_SIZE_BYTES) {
                continue;
            }

            Entry entry = new Entry();
            entry.title = stripExtension(name);
            entry.location = Uri.fromFile(file).toString();
            entry.folder = dir.getName();
            entry.sizeBytes = file.length();
            entry.modifiedSec = file.lastModified() / 1_000;
            entry.isAudio = isAudio;
            entries.put(file.getAbsolutePath(), entry);

            if (entries.size() >= MAX_FILES) {
                return;
            }
        }
    }

    private static boolean isSkipped(String path) {
        String lower = path.toLowerCase(Locale.US);

        for (String folder : SKIPPED_FOLDERS) {
            if (lower.contains("/" + folder + "/")) {
                return true;
            }
        }

        return false;
    }

    private static Video toVideo(Entry entry) {
        Video video = new Video();
        // NOTE: an item without an id isn't a video for the app. Stable: the position of the playback is remembered by it.
        video.videoId = Video.LOCAL_ID_PREFIX + Integer.toHexString(entry.location.hashCode());
        video.title = entry.title;
        video.localUri = entry.location;
        // The first frame of a video; nothing for the music
        video.cardImageUrl = entry.isAudio ? null : entry.location;

        StringBuilder info = new StringBuilder();
        append(info, entry.folder);
        append(info, entry.durationMs > 0 ? formatDuration(entry.durationMs) : null);
        append(info, formatSize(entry.sizeBytes));
        video.secondTitle = info.toString();
        video.author = entry.folder;

        return video;
    }

    private static void append(StringBuilder builder, String part) {
        if (part == null || part.isEmpty()) {
            return;
        }

        if (builder.length() > 0) {
            builder.append(" • ");
        }

        builder.append(part);
    }

    private static String formatDuration(long durationMs) {
        long seconds = durationMs / 1_000;
        long hours = seconds / 3_600;
        long minutes = seconds % 3_600 / 60;

        return hours > 0 ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds % 60) :
                String.format(Locale.US, "%d:%02d", minutes, seconds % 60);
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f GB", bytes / (1024d * 1024 * 1024));
        }

        return String.format(Locale.US, "%d MB", Math.max(1, Math.round(bytes / (1024d * 1024))));
    }

    private static String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && name.length() - dot <= 6 ? name.substring(0, dot) : name;
    }

    private static boolean isSame(List<Video> left, List<Video> right) {
        if (left.size() != right.size()) {
            return false;
        }

        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).localUri.equals(right.get(i).localUri)) {
                return false;
            }
        }

        return true;
    }
}
