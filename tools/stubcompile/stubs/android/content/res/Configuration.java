package android.content.res;

/** Stub of android.content.res.Configuration. */
public class Configuration {

    public static final int ORIENTATION_UNDEFINED = 0;
    public static final int ORIENTATION_PORTRAIT = 1;
    public static final int ORIENTATION_LANDSCAPE = 2;
    public static final int ORIENTATION_SQUARE = 3;

    public static final int SCREENLAYOUT_SIZE_UNDEFINED = 0;
    public static final int SCREENLAYOUT_SIZE_SMALL = 1;
    public static final int SCREENLAYOUT_SIZE_NORMAL = 2;
    public static final int SCREENLAYOUT_SIZE_LARGE = 3;
    public static final int SCREENLAYOUT_SIZE_XLARGE = 4;

    public static final int UI_MODE_TYPE_UNDEFINED = 0;
    public static final int UI_MODE_NIGHT_NO = 0x10;
    public static final int UI_MODE_NIGHT_YES = 0x20;

    public static final int KEYBOARD_UNDEFINED = 0;
    public static final int KEYBOARD_NOKEYS = 1;
    public static final int KEYBOARD_QWERTY = 2;
    public static final int KEYBOARD_12KEY = 3;

    public int orientation = ORIENTATION_UNDEFINED;
    public int screenLayout = SCREENLAYOUT_SIZE_UNDEFINED;
    public int uiMode = UI_MODE_TYPE_UNDEFINED;
    public int keyboard = KEYBOARD_UNDEFINED;
    public int keyboardHidden = 0;
    public int hardKeyboardHidden = 0;
    public int densityDpi = 160;
    public int smallestScreenWidthDp = 320;
    public int screenWidthDp = 320;
    public int screenHeightDp = 480;
    public java.util.Locale locale;

    public Configuration() { }

    public Configuration(Configuration other) { }
}
