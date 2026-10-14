package android.util;

/**
 * Stand-in for the Android logger so that the app's classes can run on a desktop JVM
 * (android.jar only contains stubs that throw).
 */
public final class Log {
    private Log() {
    }

    public static int v(String tag, String msg) {
        return 0;
    }

    public static int d(String tag, String msg) {
        return 0;
    }

    public static int i(String tag, String msg) {
        return 0;
    }

    public static int w(String tag, String msg) {
        return 0;
    }

    public static int e(String tag, String msg) {
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        return 0;
    }

    public static boolean isLoggable(String tag, int level) {
        return false;
    }
}
