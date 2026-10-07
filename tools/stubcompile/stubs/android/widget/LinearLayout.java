package android.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

/** Stub of android.widget.LinearLayout, including LayoutParams. */
public class LinearLayout extends ViewGroup {

    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    public static final int SHOW_DIVIDER_NONE = 0;
    public static final int SHOW_DIVIDER_BEGINNING = 1;
    public static final int SHOW_DIVIDER_MIDDLE = 2;
    public static final int SHOW_DIVIDER_END = 4;

    public LinearLayout(Context context) { super(context); }

    public LinearLayout(Context context, AttributeSet attrs) { super(context, attrs); }

    public LinearLayout(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    public void setOrientation(int orientation) { }

    public int getOrientation() { return VERTICAL; }

    public void setGravity(int gravity) { }

    public int getGravity() { return 0; }

    public void setWeightSum(float weightSum) { }

    public float getWeightSum() { return 0f; }

    public void setShowDividers(int showDividers) { }

    public void setDividerDrawable(android.graphics.drawable.Drawable divider) { }

    public void setBaselineAligned(boolean baselineAligned) { }

    public void setMeasureWithLargestChildEnabled(boolean enabled) { }

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {

        public float weight;
        public int gravity;

        public LayoutParams(int width, int height) { super(width, height); }

        public LayoutParams(int width, int height, float weight) {
            super(width, height);
            this.weight = weight;
        }

        public LayoutParams(LayoutParams source) {
            super(source);
            this.weight = source.weight;
            this.gravity = source.gravity;
        }

        public LayoutParams(ViewGroup.LayoutParams source) { super(source); }

        public LayoutParams(ViewGroup.MarginLayoutParams source) { super(source); }
    }
}
