package android.text.style;

import android.graphics.Typeface;
import android.text.TextPaint;

/** Stub of android.text.style.StyleSpan. */
public class StyleSpan extends CharacterStyle {

    private final int style;

    public StyleSpan(int style) { this.style = style; }

    public int getStyle() { return style; }

    public void updateDrawState(TextPaint tp) { }
}
