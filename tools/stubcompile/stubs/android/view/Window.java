package android.view;

import android.graphics.drawable.Drawable;

/** Stub of android.view.Window. */
public class Window {

    public static final int FEATURE_NO_TITLE = 1;
    public static final int FEATURE_ACTION_BAR = 8;
    public static final int FEATURE_ACTION_BAR_OVERLAY = 9;
    public static final int FEATURE_INDETERMINATE_PROGRESS = 5;
    public static final int FEATURE_PROGRESS = 2;

    public Window() { }

    public void setFlags(int flags, int mask) { }

    public void addFlags(int flags) { }

    public void clearFlags(int flags) { }

    public void setSoftInputMode(int mode) { }

    public void setLayout(int width, int height) { }

    public void setBackgroundDrawable(Drawable drawable) { }

    public void setTitle(CharSequence title) { }

    public void setTitle(int titleId) { }

    public void requestFeature(int featureId) { }

    public boolean hasFeature(int featureId) { return false; }

    public View getDecorView() { return null; }

    public View findViewById(int id) { return null; }

    public void setDimAmount(float amount) { }

    public void takeInputQueue(Object queue) { }

    public boolean isFloating() { return false; }
}
