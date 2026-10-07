package android.graphics;

/** Stub of android.graphics.Canvas (only so Drawable.draw can be declared). */
public class Canvas {

    public Canvas() { }

    public Canvas(android.graphics.Bitmap bitmap) { }

    public int getWidth() { return 0; }

    public int getHeight() { return 0; }

    public void drawColor(int color) { }

    public void drawText(String text, float x, float y, Paint paint) { }

    public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) { }

    public int save() { return 0; }

    public void restore() { }
}
