package android.view;

/** Stub of android.view.MotionEvent (needed for View.OnTouchListener). */
public class MotionEvent {

    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_OUTSIDE = 4;

    public MotionEvent() { }

    public int getAction() { return ACTION_DOWN; }

    public int getActionMasked() { return ACTION_DOWN; }

    public float getX() { return 0f; }

    public float getY() { return 0f; }

    public float getRawX() { return 0f; }

    public float getRawY() { return 0f; }

    public long getEventTime() { return 0L; }

    public long getDownTime() { return 0L; }
}
