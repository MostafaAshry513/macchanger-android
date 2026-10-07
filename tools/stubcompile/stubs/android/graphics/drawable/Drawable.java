package android.graphics.drawable;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;

/** Stub of android.graphics.drawable.Drawable. */
public class Drawable {

    public static final int CENTER = 1;

    public Drawable() { }

    public void setAlpha(int alpha) { }

    public int getAlpha() { return 255; }

    public void setColorFilter(ColorFilter colorFilter) { }

    public void setColorFilter(int color, android.graphics.PorterDuff.Mode mode) { }

    public void clearColorFilter() { }

    public void setBounds(int left, int top, int right, int bottom) { }

    public void setBounds(Rect bounds) { }

    public Rect getBounds() { return new Rect(); }

    public int getIntrinsicWidth() { return -1; }

    public int getIntrinsicHeight() { return -1; }

    public int getMinimumWidth() { return 0; }

    public int getMinimumHeight() { return 0; }

    public void setVisible(boolean visible, boolean restart) { }

    public boolean isVisible() { return true; }

    public boolean isStateful() { return false; }

    public boolean setState(int[] stateSet) { return false; }

    public int[] getState() { return new int[0]; }

    public void invalidateSelf() { }

    public void draw(Canvas canvas) { }

    public void setDither(boolean dither) { }

    public void setFilterBitmap(boolean filter) { }

    public Drawable getCurrent() { return this; }
}
