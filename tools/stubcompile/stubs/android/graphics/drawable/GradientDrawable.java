package android.graphics.drawable;

import android.graphics.drawable.Drawable;

/** Stub of android.graphics.drawable.GradientDrawable, including Orientation and the shape kinds. */
public class GradientDrawable extends Drawable {

    public enum Orientation {
        TOP_BOTTOM, TR_BL, RIGHT_LEFT, BR_TL, BOTTOM_TOP, BL_TR, LEFT_RIGHT, TL_BR
    }

    public static final int RECTANGLE = 0;
    public static final int OVAL = 1;
    public static final int LINE = 2;
    public static final int RING = 3;

    public static final int LINEAR_GRADIENT = 0;
    public static final int RADIAL_GRADIENT = 1;
    public static final int SWEEP_GRADIENT = 2;

    public static final int CLIP_PATH = 1;
    public static final int PAD_LAYER = 0;
    public static final int AXIS_TOP = 1;
    public static final int AXIS_BOTTOM = 2;
    public static final int AXIS_LEFT = 3;
    public static final int AXIS_RIGHT = 4;

    public GradientDrawable() { }

    public GradientDrawable(Orientation orientation, int[] colors) { }

    public void setColor(int argb) { }

    public void setColor(android.content.res.ColorStateList colorStateList) { }

    public void setColors(int[] colors) { }

    public void setCornerRadius(float radius) { }

    public void setCornerRadii(float[] radii) { }

    public void setStroke(int width, int color) { }

    public void setStroke(int width, int color, float dashWidth, float dashGap) { }

    public void setShape(int shape) { }

    public void setGradientType(int gradient) { }

    public void setGradientRadius(float gradientRadius) { }

    public void setSize(int width, int height) { }

    public void setUseLevel(boolean useLevel) { }

    public void setPadding(int left, int top, int right, int bottom) { }
}
