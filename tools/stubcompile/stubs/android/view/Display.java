package android.view;

import android.util.DisplayMetrics;

/** Stub of android.view.Display. */
public class Display {

    public static final int DEFAULT_DISPLAY = 0;
    public static final int ROTATION_0 = 0;
    public static final int ROTATION_90 = 1;
    public static final int ROTATION_180 = 2;
    public static final int ROTATION_270 = 3;

    protected Display() { }

    public int getWidth() { return 1080; }

    public int getHeight() { return 1920; }

    public int getRotation() { return ROTATION_0; }

    public void getMetrics(DisplayMetrics outMetrics) { }

    public void getRealMetrics(DisplayMetrics outMetrics) { }
}
