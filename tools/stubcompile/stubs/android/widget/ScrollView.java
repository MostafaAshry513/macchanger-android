package android.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

/** Stub of android.widget.ScrollView. Declared as a plain ViewGroup: sufficient for the app. */
public class ScrollView extends FrameLayout {

    public ScrollView(Context context) { super(context); }

    public ScrollView(Context context, AttributeSet attrs) { super(context, attrs); }

    public ScrollView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    public void setFillViewport(boolean fillViewport) { }

    public boolean isFillViewport() { return false; }

    public boolean fullScroll(int direction) { return true; }

    public boolean pageScroll(int direction) { return true; }

    public boolean arrowScroll(int direction) { return true; }

    public void smoothScrollTo(int x, int y) { }

    public void smoothScrollBy(int dx, int dy) { }

    public void scrollTo(int x, int y) { }

    public void setSmoothScrollingEnabled(boolean smoothScrollingEnabled) { }

    public int getMaxScrollAmount() { return 0; }

    public void addView(View child) { super.addView(child); }
}
