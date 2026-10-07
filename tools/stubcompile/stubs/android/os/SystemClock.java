package android.os;

/** Stub of android.os.SystemClock. */
public final class SystemClock {

    private SystemClock() { }

    public static void sleep(long ms) { }

    public static long uptimeMillis() { return 0L; }

    public static long elapsedRealtime() { return 0L; }

    public static long currentThreadTimeMillis() { return 0L; }

    public static boolean setCurrentTimeMillis(long millis) { return true; }
}
