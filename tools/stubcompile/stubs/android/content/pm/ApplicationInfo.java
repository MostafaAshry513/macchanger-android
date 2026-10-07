package android.content.pm;

/** Stub of android.content.pm.ApplicationInfo. */
public class ApplicationInfo {

    public static final int FLAG_SYSTEM = 1 << 0;
    public static final int FLAG_DEBUGGABLE = 1 << 1;
    public static final int FLAG_EXTERNAL_STORAGE = 1 << 18;

    public String packageName;
    public String name;
    public String sourceDir;
    public String dataDir;
    public int uid;
    public int flags;
    public int labelRes;
    public int icon;
    public boolean enabled = true;

    public ApplicationInfo() { }

    public CharSequence loadLabel(PackageManager pm) { return null; }
}
