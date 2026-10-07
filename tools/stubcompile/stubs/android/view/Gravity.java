package android.view;

/** Stub of android.view.Gravity. Values are arbitrary but self-consistent. */
public class Gravity {

    public static final int NO_GRAVITY = 0x0000;
    public static final int CENTER_HORIZONTAL = 0x0001;
    public static final int LEFT = 0x0003;
    public static final int RIGHT = 0x0005;
    public static final int CENTER_VERTICAL = 0x0010;
    public static final int TOP = 0x0030;
    public static final int BOTTOM = 0x0050;
    public static final int CENTER = 0x0011;
    public static final int START = 0x00800003;
    public static final int END = 0x00800005;
    public static final int FILL = 0x0077;
    public static final int FILL_HORIZONTAL = 0x0007;
    public static final int FILL_VERTICAL = 0x0070;
    public static final int CLIP_HORIZONTAL = 0x0008;
    public static final int CLIP_VERTICAL = 0x0080;
    public static final int RELATIVE_LAYOUT_DIRECTION = 0x00800000;
    public static final int DISPLAY_CLIP_HORIZONTAL = 0x01000000;
    public static final int DISPLAY_CLIP_VERTICAL = 0x10000000;

    protected Gravity() { }

    public static int getAbsoluteGravity(int gravity, int layoutDirection) { return gravity; }

    public static boolean isHorizontal(int gravity) { return false; }

    public static boolean isVertical(int gravity) { return false; }
}
