package android.text.style;

import android.text.TextPaint;

/** Stub of android.text.style.ForegroundColorSpan. */
public class ForegroundColorSpan extends CharacterStyle {

    private final int color;

    public ForegroundColorSpan(int color) { this.color = color; }

    public int getForegroundColor() { return color; }

    public void updateDrawState(TextPaint tp) { }
}
