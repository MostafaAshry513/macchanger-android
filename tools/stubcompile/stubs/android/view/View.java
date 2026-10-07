package android.view;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

/** Stub of android.view.View. */
public class View {

    public static final int VISIBLE = 0x00000000;
    public static final int INVISIBLE = 0x00000004;
    public static final int GONE = 0x00000008;

    public static final int FOCUS_UP = 0x00000021;
    public static final int FOCUS_DOWN = 0x00000082;
    public static final int FOCUS_LEFT = 0x00000011;
    public static final int FOCUS_RIGHT = 0x00000042;
    public static final int FOCUS_FORWARD = 0x00000002;
    public static final int FOCUS_BACKWARD = 0x00000001;

    public static final int TEXT_ALIGNMENT_INHERIT = 0;
    public static final int TEXT_ALIGNMENT_GRAVITY = 1;
    public static final int TEXT_ALIGNMENT_TEXT_START = 2;
    public static final int TEXT_ALIGNMENT_TEXT_END = 3;
    public static final int TEXT_ALIGNMENT_CENTER = 4;
    public static final int TEXT_ALIGNMENT_VIEW_START = 5;
    public static final int TEXT_ALIGNMENT_VIEW_END = 6;

    public static final int SCROLL_AXIS_NONE = 0;
    public static final int SCROLL_AXIS_HORIZONTAL = 1;
    public static final int SCROLL_AXIS_VERTICAL = 2;

    public static final int OVER_SCROLL_ALWAYS = 0;
    public static final int OVER_SCROLL_IF_CONTENT_SCROLLS = 1;
    public static final int OVER_SCROLL_NEVER = 2;

    private final Context context;

    public View(Context context) { this.context = context; }

    public View(Context context, AttributeSet attrs) { this.context = context; }

    public View(Context context, AttributeSet attrs, int defStyleAttr) { this.context = context; }

    public Context getContext() { return context; }

    public int getId() { return -1; }

    public void setId(int id) { }

    public int getVisibility() { return VISIBLE; }

    public void setVisibility(int visibility) { }

    public void setPadding(int left, int top, int right, int bottom) { }

    public void setPaddingRelative(int start, int top, int end, int bottom) { }

    public int getPaddingLeft() { return 0; }

    public int getPaddingTop() { return 0; }

    public int getPaddingRight() { return 0; }

    public int getPaddingBottom() { return 0; }

    public void setLayoutParams(ViewGroup.LayoutParams params) { }

    public ViewGroup.LayoutParams getLayoutParams() { return null; }

    public void setBackground(Drawable background) { }

    public void setBackgroundDrawable(Drawable background) { }

    public void setBackgroundColor(int color) { }

    public void setBackgroundResource(int resid) { }

    public Drawable getBackground() { return null; }

    public void setClickable(boolean clickable) { }

    public boolean isClickable() { return false; }

    public void setEnabled(boolean enabled) { }

    public boolean isEnabled() { return true; }

    public void setFocusable(boolean focusable) { }

    public void setFocusableInTouchMode(boolean focusableInTouchMode) { }

    public boolean isFocusable() { return false; }

    public boolean requestFocus() { return true; }

    public boolean requestFocus(int direction) { return true; }

    public boolean requestFocus(int direction, android.graphics.Rect previouslyFocusedRect) { return true; }

    public void clearFocus() { }

    public boolean isFocused() { return false; }

    public boolean post(Runnable action) { if (action != null) action.run(); return true; }

    public boolean postDelayed(Runnable action, long delayMillis) { return true; }

    public boolean removeCallbacks(Runnable action) { return true; }

    public int getWidth() { return 0; }

    public int getHeight() { return 0; }

    public int getMeasuredWidth() { return 0; }

    public int getMeasuredHeight() { return 0; }

    public void setMinimumWidth(int minWidth) { }

    public void setMinimumHeight(int minHeight) { }

    public void setMinWidth(int minWidth) { }

    public void setMinHeight(int minHeight) { }

    public void setOnClickListener(OnClickListener l) { }

    public void setOnLongClickListener(OnLongClickListener l) { }

    public void setOnTouchListener(OnTouchListener l) { }

    public void setOnFocusChangeListener(OnFocusChangeListener l) { }

    public void setAlpha(float alpha) { }

    public void setTag(Object tag) { }

    public Object getTag() { return null; }

    public void setContentDescription(CharSequence contentDescription) { }

    public CharSequence getContentDescription() { return null; }

    public void setSelected(boolean selected) { }

    public boolean isSelected() { return false; }

    public void setScrollContainer(boolean isScrollContainer) { }

    public void setSaveEnabled(boolean enabled) { }

    public void setHapticFeedbackEnabled(boolean hapticFeedbackEnabled) { }

    public void setSoundEffectsEnabled(boolean soundEffectsEnabled) { }

    public void bringToFront() { }

    public void invalidate() { }

    public void requestLayout() { }

    public void setTextAlignment(int textAlignment) { }

    public void setTooltipText(CharSequence tooltipText) { }

    public void setOnGenericMotionListener(OnGenericMotionListener l) { }

    public interface OnClickListener { void onClick(View v); }

    public interface OnLongClickListener { boolean onLongClick(View v); }

    public interface OnTouchListener { boolean onTouch(View v, MotionEvent event); }

    public interface OnFocusChangeListener { void onFocusChange(View v, boolean hasFocus); }

    public interface OnGenericMotionListener { boolean onGenericMotion(View v, MotionEvent event); }
}
