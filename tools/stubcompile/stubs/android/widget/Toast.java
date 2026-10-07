package android.widget;

import android.content.Context;
import android.view.View;

/** Stub of android.widget.Toast. */
public class Toast {

    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_LONG = 1;

    protected Toast() { }

    public static Toast makeText(Context context, CharSequence text, int duration) { return new Toast(); }

    public static Toast makeText(Context context, int resId, int duration) { return new Toast(); }

    public void show() { }

    public void cancel() { }

    public void setText(CharSequence s) { }

    public void setDuration(int duration) { }

    public void setGravity(int gravity, int xOffset, int yOffset) { }

    public void setMargin(float horizontalMargin, float verticalMargin) { }

    public void setView(View view) { }

    public View getView() { return null; }
}
