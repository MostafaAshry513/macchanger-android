package android.text;

import android.graphics.Paint;

/** Stub of android.text.TextPaint. */
public class TextPaint extends Paint {

    public int bgColor;
    public int baselineShift;
    public int linkColor;
    public int[] drawableState;
    public float density = 1.0f;
    public float underlineThickness;

    public TextPaint() { super(); }

    public TextPaint(int flags) { super(flags); }

    public TextPaint(Paint p) { super(p); }
}
