package android.view;

import android.content.Context;
import android.util.AttributeSet;

/** Stub of android.view.ViewGroup, including LayoutParams and MarginLayoutParams. */
public class ViewGroup extends View {

    public ViewGroup(Context context) { super(context); }

    public ViewGroup(Context context, AttributeSet attrs) { super(context, attrs); }

    public ViewGroup(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    public void addView(View child) { }

    public void addView(View child, int index) { }

    public void addView(View child, LayoutParams params) { }

    public void addView(View child, int index, LayoutParams params) { }

    public void addView(View child, int width, int height) { }

    public void removeView(View view) { }

    public void removeViewAt(int index) { }

    public void removeAllViews() { }

    public int getChildCount() { return 0; }

    public View getChildAt(int index) { return null; }

    public void setClipChildren(boolean clipChildren) { }

    public void setClipToPadding(boolean clipToPadding) { }

    public void setDescendantFocusability(int focusability) { }

    public int getDescendantFocusability() { return 0; }

    public static final int FOCUS_BEFORE_DESCENDANTS = 0x20000;
    public static final int FOCUS_AFTER_DESCENDANTS = 0x40000;
    public static final int FOCUS_BLOCK_DESCENDANTS = 0x60000;

    public static class LayoutParams {

        public static final int FILL_PARENT = -1;
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;

        public int width;
        public int height;

        public LayoutParams(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public LayoutParams(LayoutParams source) {
            this.width = source.width;
            this.height = source.height;
        }

        public void setBaseAttributes(AttributeSet attrs, int widthAttr, int heightAttr) { }

        public String debug(String output) { return output; }

        public String toString() { return "LayoutParams"; }
    }

    public static class MarginLayoutParams extends LayoutParams {

        public int leftMargin;
        public int topMargin;
        public int rightMargin;
        public int bottomMargin;
        public int startMargin = -1;
        public int endMargin = -1;

        public MarginLayoutParams(int width, int height) { super(width, height); }

        public MarginLayoutParams(MarginLayoutParams source) {
            super(source);
            this.leftMargin = source.leftMargin;
            this.topMargin = source.topMargin;
            this.rightMargin = source.rightMargin;
            this.bottomMargin = source.bottomMargin;
        }

        public MarginLayoutParams(LayoutParams source) { super(source); }

        public void setMargins(int left, int top, int right, int bottom) {
            this.leftMargin = left;
            this.topMargin = top;
            this.rightMargin = right;
            this.bottomMargin = bottom;
        }

        public void setMarginStart(int start) { this.startMargin = start; }

        public void setMarginEnd(int end) { this.endMargin = end; }
    }
}
