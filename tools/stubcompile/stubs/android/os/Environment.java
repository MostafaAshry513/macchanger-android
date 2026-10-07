package android.os;

import java.io.File;

/** Stub of android.os.Environment. */
public class Environment {

    public static final String MEDIA_MOUNTED = "mounted";
    public static final String MEDIA_REMOVED = "removed";
    public static final String MEDIA_UNMOUNTED = "unmounted";
    public static final String DIRECTORY_DOWNLOADS = "Download";
    public static final String DIRECTORY_DOCUMENTS = "Documents";
    public static final String DIRECTORY_DCIM = "DCIM";

    protected Environment() { }

    public static File getExternalStorageDirectory() { return new File("/sdcard"); }

    public static File getExternalStoragePublicDirectory(String type) { return new File("/sdcard/" + type); }

    public static String getExternalStorageState() { return MEDIA_MOUNTED; }

    public static boolean isExternalStorageRemovable() { return false; }
}
