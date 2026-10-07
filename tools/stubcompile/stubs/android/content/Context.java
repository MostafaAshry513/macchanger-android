package android.content;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import java.io.File;

/** Stub of android.content.Context: the members the app reaches through Activity. */
public class Context {

    public static final int MODE_PRIVATE = 0x0000;
    public static final int MODE_WORLD_READABLE = 0x0001;
    public static final int MODE_WORLD_WRITEABLE = 0x0002;
    public static final int MODE_MULTI_PROCESS = 0x0004;
    public static final int MODE_APPEND = 0x8000;

    public static final String CLIPBOARD_SERVICE = "clipboard";
    public static final String WINDOW_SERVICE = "window";
    public static final String LAYOUT_INFLATER_SERVICE = "layout_inflater";
    public static final String ACTIVITY_SERVICE = "activity";
    public static final String CONNECTIVITY_SERVICE = "connectivity";
    public static final String WIFI_SERVICE = "wifi";
    public static final String POWER_SERVICE = "power";
    public static final String TELEPHONY_SERVICE = "phone";
    public static final String NOTIFICATION_SERVICE = "notification";
    public static final String INPUT_METHOD_SERVICE = "input_method";

    public Context() { }

    public Context getApplicationContext() { return this; }

    public Resources getResources() { return new Resources(); }

    public SharedPreferences getSharedPreferences(String name, int mode) { return new StubSharedPreferences(); }

    public File getFilesDir() { return new File("/data/user/0/" + getPackageName() + "/files"); }

    public File getCacheDir() { return new File("/data/user/0/" + getPackageName() + "/cache"); }

    public File getDir(String name, int mode) { return new File(getFilesDir(), name); }

    public File getExternalFilesDir(String type) {
        return new File("/sdcard/Android/data/" + getPackageName() + "/files");
    }

    public String getPackageName() { return "com.macchanger"; }

    public String getString(int resId) { return ""; }

    public String getString(int resId, Object... formatArgs) { return ""; }

    public CharSequence getText(int resId) { return ""; }

    public int getColor(int resId) { return 0; }

    public Object getSystemService(String name) { return null; }

    public void startActivity(Intent intent) { }

    public void startActivityForResult(Intent intent, int requestCode) { }

    public void startService(Intent intent) { }

    public void stopService(Intent intent) { }

    public void sendBroadcast(Intent intent) { }

    public PackageManager getPackageManager() { return new PackageManager(); }

    public ApplicationInfo getApplicationInfo() { return new ApplicationInfo(); }

    public void setTheme(int resId) { }

    public boolean isRestricted() { return false; }
}
