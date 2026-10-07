package android.content.pm;

/** Stub of android.content.pm.PackageInfo. */
public class PackageInfo {

    public String packageName;
    public String versionName;
    public int versionCode;
    public long firstInstallTime;
    public long lastUpdateTime;
    public ApplicationInfo applicationInfo;

    public PackageInfo() { }

    public String toString() { return packageName; }
}
