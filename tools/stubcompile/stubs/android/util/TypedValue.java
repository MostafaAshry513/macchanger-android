package android.util;

/** Stub of android.util.TypedValue: the unit constants used by setTextSize(int, float). */
public class TypedValue {

    public static final int COMPLEX_UNIT_PX = 0;
    public static final int COMPLEX_UNIT_DIP = 1;
    public static final int COMPLEX_UNIT_SP = 2;
    public static final int COMPLEX_UNIT_PT = 3;
    public static final int COMPLEX_UNIT_IN = 4;
    public static final int COMPLEX_UNIT_MM = 5;

    public static final int TYPE_NULL = 0x00;
    public static final int TYPE_REFERENCE = 0x01;
    public static final int TYPE_ATTRIBUTE = 0x02;
    public static final int TYPE_STRING = 0x03;
    public static final int TYPE_FLOAT = 0x04;
    public static final int TYPE_DIMENSION = 0x05;
    public static final int TYPE_FRACTION = 0x06;
    public static final int TYPE_INT_DEC = 0x10;
    public static final int TYPE_INT_HEX = 0x11;
    public static final int TYPE_INT_BOOLEAN = 0x12;

    public static final int TYPE_FIRST_COLOR_INT = 0x1c;
    public static final int TYPE_LAST_COLOR_INT = 0x1f;
    public static final int TYPE_FIRST_INT = 0x10;
    public static final int TYPE_LAST_INT = 0x1f;

    public static final int DATA_NULL_UNDEFINED = 0;
    public static final int DATA_NULL_EMPTY = 1;

    public int type;
    public int data;
    public float floatValue;
    public int density;
    public int assetCookie;
    public int resourceId;
    public int changingConfigurations;
    public CharSequence string;
    public Object cookie;

    public TypedValue() { }

    public static float applyDimension(int unit, float value, DisplayMetrics metrics) { return value; }

    public static float complexToFloat(int complex) { return 0f; }

    public static float complexToDimension(int data, DisplayMetrics metrics) { return 0f; }

    public static int complexToDimensionPixelSize(int data, DisplayMetrics metrics) { return 0; }

    public static int complexToDimensionPixelOffset(int data, DisplayMetrics metrics) { return 0; }

    public float getFloat() { return floatValue; }

    public int getComplexUnit() { return COMPLEX_UNIT_PX; }

    public void setTo(TypedValue other) { }

    public final CharSequence coerceToString() { return string; }

    public static String coerceToString(int type, int data) { return ""; }

    public String toString() { return "TypedValue"; }
}
