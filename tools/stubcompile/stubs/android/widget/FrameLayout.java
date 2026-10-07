package android.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

/** Stub of android.widget.FrameLayout. */
public class FrameLayout extends ViewGroup {

    public FrameLayout(Context context) { super(context); }

    public FrameLayout(Context context, AttributeSet attrs) { super(context, attrs); }

    public FrameLayout(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    public void setForeground(android.graphics.drawable.Drawable foreground) { }

    public void setMeasureAllChildren(boolean measureAll) { }

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {

        public int gravity;

        public LayoutParams(int width, int height) { super(width, height); }

        public LayoutParams(ViewGroup.LayoutParams source) { super(source); }
    }
}
