package android.content.res;

/** Stub of android.content.res.ColorStateList. */
public class ColorStateList {

    protected ColorStateList() { }

    public static ColorStateList valueOf(int color) { return new ColorStateList(); }

    public static ColorStateList createFromColors(int[] colors, int[][] stateSpecs) { return new ColorStateList(); }

    public int getDefaultColor() { return 0; }

    public int getColorForState(int[] stateSet, int defaultColor) { return defaultColor; }
}
