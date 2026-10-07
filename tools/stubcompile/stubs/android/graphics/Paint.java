package android.graphics;

/** Stub of android.graphics.Paint. */
public class Paint {

    public static final int ANTI_ALIAS_FLAG = 0x01;
    public static final int UNDERLINE_TEXT_FLAG = 0x08;
    public static final int STRIKE_THRU_TEXT_FLAG = 0x10;
    public static final int FAKE_BOLD_TEXT_FLAG = 0x20;
    public static final int SUBPIXEL_TEXT_FLAG = 0x80;
    public static final int DEV_KERN_TEXT_FLAG = 0x100;

    public static final int Align_LEFT = 0;
    public static final int Align_CENTER = 1;
    public static final int Align_RIGHT = 2;

    public static class Align {
        public static final int LEFT = 0;
        public static final int CENTER = 1;
        public static final int RIGHT = 2;
        protected Align() { }
    }

    public static class Style {
        public static final int FILL = 0;
        public static final int STROKE = 1;
        public static final int FILL_AND_STROKE = 2;
        protected Style() { }
    }

    public Paint() { }

    public Paint(int flags) { }

    public Paint(Paint paint) { }

    public void setColor(int color) { }

    public int getColor() { return 0; }

    public void setAntiAlias(boolean aa) { }

    public void setFakeBoldText(boolean fakeBoldText) { }

    public void setUnderlineText(boolean underlineText) { }

    public void setStrikeThruText(boolean strikeThruText) { }

    public void setTextSize(float textSize) { }

    public float getTextSize() { return 0f; }

    public void setTextSkewX(float skewX) { }

    public void setTextScaleX(float scaleX) { }

    public void setLetterSpacing(float letterSpacing) { }

    public float getLetterSpacing() { return 0f; }

    public void setTypeface(Typeface typeface) { }

    public Typeface getTypeface() { return null; }

    public void setStrokeWidth(float width) { }

    public void setStyle(Style style) { }

    public void setFlags(int flags) { }

    public int getFlags() { return 0; }

    public void setAlpha(int a) { }

    public void setColorFilter(ColorFilter filter) { }

    public void setXfermode(Xfermode xfermode) { }

    public void setShadowLayer(float radius, float dx, float dy, int color) { }

    public void clearShadowLayer() { }

    public float measureText(String text) { return 0f; }

    public float measureText(CharSequence text, int start, int end) { return 0f; }

    public int breakText(String text, boolean measureForwards, float maxWidth, float[] measuredWidth) { return 0; }

    public void getTextBounds(String text, int start, int end, Rect bounds) { }

    public void getTextBounds(char[] text, int index, int count, Rect bounds) { }

    public float ascent() { return 0f; }

    public float descent() { return 0f; }

    public void reset() { }
}
