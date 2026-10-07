package android.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;

/** Stub of android.widget.TextView. */
public class TextView extends View {

    public TextView(Context context) { super(context); }

    public TextView(Context context, AttributeSet attrs) { super(context, attrs); }

    public TextView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    public void setText(CharSequence text) { }

    public void setText(CharSequence text, BufferType type) { }

    public void setText(int resId) { }

    public void setText(char[] text, int start, int len) { }

    public CharSequence getText() { return ""; }

    public Editable getEditableText() { return new android.text.SpannableStringBuilder(); }

    public void append(CharSequence text) { }

    public void setTextColor(int color) { }

    public void setTextColor(android.content.res.ColorStateList colors) { }

    public void setTextSize(float size) { }

    public void setTextSize(int unit, float size) { }

    public void setTypeface(Typeface tf) { }

    public void setTypeface(Typeface tf, int style) { }

    public Typeface getTypeface() { return null; }

    public void setLetterSpacing(float letterSpacing) { }

    public float getLetterSpacing() { return 0f; }

    public void setAllCaps(boolean allCaps) { }

    public void setGravity(int gravity) { }

    public int getGravity() { return Gravity.NO_GRAVITY; }

    public void setHint(CharSequence hint) { }

    public void setHint(int resId) { }

    public CharSequence getHint() { return null; }

    public void setHintTextColor(int color) { }

    public void setHintTextColor(android.content.res.ColorStateList colors) { }

    public void setInputType(int type) { }

    public void setSingleLine() { }

    public void setSingleLine(boolean singleLine) { }

    public void setMaxLines(int maxlines) { }

    public int getMaxLines() { return -1; }

    public void setMinLines(int minLines) { }

    public void setLines(int lines) { }

    public void setEllipsize(TextUtils.TruncateAt where) { }

    public TextUtils.TruncateAt getEllipsize() { return null; }

    public void setTextIsSelectable(boolean selectable) { }

    public boolean isTextSelectable() { return false; }

    public void setLineSpacing(float add, float mult) { }

    public void setTextAppearance(int resId) { }

    public void setHorizontallyScrolling(boolean whether) { }

    public void setIncludeFontPadding(boolean includepad) { }

    public void setShadowLayer(float radius, float dx, float dy, int color) { }

    public void setSelectAllOnFocus(boolean selectAllOnFocus) { }

    public void setFreezesText(boolean freezesText) { }

    public void setTextScaleX(float size) { }

    public void setMarqueeRepeatLimit(int marqueeLimit) { }

    public void setElegantTextHeight(boolean elegant) { }

    public void setBreakStrategy(int breakStrategy) { }

    public void setHyphenationFrequency(int hyphenationFrequency) { }

    public BufferType getBufferType() { return BufferType.NORMAL; }

    /** Minimal stand-in for TextView.BufferType. */
    public enum BufferType { NORMAL, SPANNABLE, EDITABLE }
}
