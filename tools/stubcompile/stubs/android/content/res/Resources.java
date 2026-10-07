package android.content.res;

import android.util.DisplayMetrics;

/** Stub of android.content.res.Resources. The app ships no res/, so only metrics matter. */
public class Resources {

    public Resources() { }

    public DisplayMetrics getDisplayMetrics() { return new DisplayMetrics(); }

    public Configuration getConfiguration() { return new Configuration(); }

    public String getString(int id) { return ""; }

    public String getString(int id, Object... formatArgs) { return ""; }

    public CharSequence getText(int id) { return ""; }

    public int getColor(int id) { return 0; }

    public float getDimension(int id) { return 0f; }

    public int getDimensionPixelSize(int id) { return 0; }

    public int getInteger(int id) { return 0; }

    public boolean getBoolean(int id) { return false; }

    public int getIdentifier(String name, String defType, String defPackage) { return 0; }
}
