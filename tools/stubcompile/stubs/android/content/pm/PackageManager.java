package android.content.pm;

/**
 * Stub of android.content.pm.PackageManager.
 *
 * getPackageInfo keeps the real checked exception: code that reads the app version must
 * handle NameNotFoundException on a device, so the gate must require it too.
 */
public class PackageManager {

    public static final int GET_ACTIVITIES = 0x00000001;
    public static final int GET_META_DATA = 0x00000080;
    public static final int GET_PERMISSIONS = 0x00001000;
    public static final int GET_SIGNATURES = 0x00000040;
    public static final int GET_SIGNING_CERTIFICATES = 0x08000000;

    public PackageManager() { }

    public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
        throw new NameNotFoundException(packageName);
    }

    public ApplicationInfo getApplicationInfo(String packageName, int flags) throws NameNotFoundException {
        throw new NameNotFoundException(packageName);
    }

    public CharSequence getApplicationLabel(ApplicationInfo info) { return ""; }

    public String getInstallerPackageName(String packageName) { return null; }

    public boolean isPackageInstalled(String packageName, int flags) { return false; }

    public static class NameNotFoundException extends Exception {

        public NameNotFoundException() { super(); }

        public NameNotFoundException(String name) { super(name); }
    }
}
