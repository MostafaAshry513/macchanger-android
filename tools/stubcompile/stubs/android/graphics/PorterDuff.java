package android.graphics;

/** Stub of android.graphics.PorterDuff. */
public class PorterDuff {

    protected PorterDuff() { }

    public enum Mode {
        CLEAR, SRC, DST, SRC_OVER, DST_OVER, SRC_IN, DST_IN, SRC_OUT, DST_OUT,
        SRC_ATOP, DST_ATOP, XOR, DARKEN, LIGHTEN, MULTIPLY, SCREEN, ADD, OVERLAY
    }

    public static class PorterDuffXfermode extends android.graphics.Xfermode {
        public PorterDuffXfermode(Mode mode) { }
    }
}
