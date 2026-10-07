package android.view;

/** Stub of android.view.KeyEvent (needed for EditText.OnEditorActionListener). */
public class KeyEvent {

    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MULTIPLE = 2;

    public static final int KEYCODE_ENTER = 66;
    public static final int KEYCODE_DPAD_CENTER = 23;
    public static final int KEYCODE_DEL = 67;
    public static final int KEYCODE_BACK = 4;

    protected KeyEvent() { }

    public int getAction() { return ACTION_DOWN; }

    public int getKeyCode() { return 0; }

    public int getRepeatCount() { return 0; }
}
