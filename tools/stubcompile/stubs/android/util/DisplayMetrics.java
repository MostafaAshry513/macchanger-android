package android.util;

/** Stub of android.util.DisplayMetrics. */
public class DisplayMetrics {

    public static final int DENSITY_LOW = 120;
    public static final int DENSITY_MEDIUM = 160;
    public static final int DENSITY_HIGH = 240;
    public static final int DENSITY_XHIGH = 320;
    public static final int DENSITY_XXHIGH = 480;
    public static final int DENSITY_XXXHIGH = 640;

    public int widthPixels = 1080;
    public int heightPixels = 1920;
    public float density = 1.0f;
    public int densityDpi = DENSITY_MEDIUM;
    public float scaledDensity = 1.0f;
    public float xdpi = 160f;
    public float ydpi = 160f;

    public DisplayMetrics() { }

    public void setTo(DisplayMetrics other) { }
}
