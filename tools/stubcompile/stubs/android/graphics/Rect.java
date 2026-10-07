package android.graphics;

/** Stub of android.graphics.Rect. */
public class Rect {

    public int left;
    public int top;
    public int right;
    public int bottom;

    public Rect() { }

    public Rect(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public int width() { return right - left; }

    public int height() { return bottom - top; }

    public void set(int left, int top, int right, int bottom) { }

    public boolean isEmpty() { return true; }

    public String toString() { return "Rect"; }
}
