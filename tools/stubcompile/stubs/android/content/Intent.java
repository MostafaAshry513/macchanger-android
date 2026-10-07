package android.content;

import android.net.Uri;

/** Stub of android.content.Intent. */
public class Intent {

    public static final String ACTION_MAIN = "android.intent.action.MAIN";
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final String ACTION_SEND = "android.intent.action.SEND";
    public static final String ACTION_EDIT = "android.intent.action.EDIT";
    public static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";

    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
    public static final int FLAG_ACTIVITY_NO_HISTORY = 0x40000000;
    public static final int FLAG_ACTIVITY_CLEAR_TASK = 0x00008000;
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;

    public Intent() { }

    public Intent(String action) { }

    public Intent(String action, Uri uri) { }

    public Intent(Context packageContext, Class<?> cls) { }

    public Intent(Intent other) { }

    public Intent setAction(String action) { return this; }

    public String getAction() { return null; }

    public Intent setData(Uri data) { return this; }

    public Uri getData() { return null; }

    public Intent setType(String type) { return this; }

    public String getType() { return null; }

    public Intent setClassName(String packageName, String className) { return this; }

    public Intent setClass(Context packageContext, Class<?> cls) { return this; }

    public Intent setPackage(String packageName) { return this; }

    public Intent putExtra(String name, String value) { return this; }

    public Intent putExtra(String name, CharSequence value) { return this; }

    public Intent putExtra(String name, int value) { return this; }

    public Intent putExtra(String name, long value) { return this; }

    public Intent putExtra(String name, float value) { return this; }

    public Intent putExtra(String name, boolean value) { return this; }

    public Intent putExtra(String name, byte[] value) { return this; }

    public String getStringExtra(String name) { return null; }

    public CharSequence getCharSequenceExtra(String name) { return null; }

    public int getIntExtra(String name, int defaultValue) { return defaultValue; }

    public long getLongExtra(String name, long defaultValue) { return defaultValue; }

    public float getFloatExtra(String name, float defaultValue) { return defaultValue; }

    public boolean getBooleanExtra(String name, boolean defaultValue) { return defaultValue; }

    public byte[] getByteArrayExtra(String name) { return null; }

    public Intent addFlags(int flags) { return this; }

    public Intent setFlags(int flags) { return this; }

    public int getFlags() { return 0; }

    public String toString() { return "Intent"; }
}
