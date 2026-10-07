package android.view;

/** Stub of android.view.WindowManager and its LayoutParams (FLAG_SECURE lives here). */
public interface WindowManager {

    void addView(View view, ViewGroup.LayoutParams params);

    void updateViewLayout(View view, ViewGroup.LayoutParams params);

    void removeView(View view);

    Display getDefaultDisplay();

    class LayoutParams extends ViewGroup.LayoutParams {

        public static final int FLAG_SECURE = 0x00002000;
        public static final int FLAG_KEEP_SCREEN_ON = 0x00000080;
        public static final int FLAG_SHOW_WHEN_LOCKED = 0x00080000;
        public static final int FLAG_DISMISS_KEYGUARD = 0x00400000;
        public static final int FLAG_TURN_SCREEN_ON = 0x00200000;
        public static final int FLAG_FULLSCREEN = 0x00000400;
        public static final int FLAG_LAYOUT_IN_SCREEN = 0x00000100;
        public static final int FLAG_LAYOUT_NO_LIMITS = 0x00000200;
        public static final int FLAG_NOT_FOCUSABLE = 0x00000008;
        public static final int FLAG_NOT_TOUCHABLE = 0x00000010;
        public static final int FLAG_NOT_TOUCH_MODAL = 0x00000020;
        public static final int FLAG_ALT_FOCUSABLE_IM = 0x00020000;
        public static final int FLAG_WATCH_OUTSIDE_TOUCH = 0x00040000;
        public static final int FLAG_HARDWARE_ACCELERATED = 0x01000000;

        public static final int LAYOUT_IN_SCREEN = 0x00000100;
        public static final int LAYOUT_NO_LIMITS = 0x00000200;
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;
        public static final int FILL_PARENT = -1;

        public static final int SOFT_INPUT_STATE_UNCHANGED = 0;
        public static final int SOFT_INPUT_STATE_HIDDEN = 0x00000002;
        public static final int SOFT_INPUT_STATE_ALWAYS_HIDDEN = 0x00000003;
        public static final int SOFT_INPUT_STATE_VISIBLE = 0x00000004;
        public static final int SOFT_INPUT_STATE_ALWAYS_VISIBLE = 0x00000005;
        public static final int SOFT_INPUT_ADJUST_RESIZE = 0x00000010;
        public static final int SOFT_INPUT_ADJUST_PAN = 0x00000020;
        public static final int SOFT_INPUT_ADJUST_NOTHING = 0x00000030;
        public static final int SOFT_INPUT_MASK_ADJUST = 0x000000f0;
        public static final int SOFT_INPUT_MASK_STATE = 0x0000000f;

        public static final int TYPE_APPLICATION = 2;
        public static final int TYPE_APPLICATION_OVERLAY = 2038;
        public static final int TYPE_SYSTEM_ALERT = 2003;
        public static final int TYPE_TOAST = 2005;

        public static final int FORMAT_TRANSLUCENT = -3;
        public static final int FORMAT_OPAQUE = -1;

        public static final int FIRST_SYSTEM_WINDOW = 2000;

        public int x;
        public int y;
        public int width;
        public int height;
        public int gravity;
        public int horizontalMargin;
        public int verticalMargin;
        public int format = FORMAT_OPAQUE;
        public int flags;
        public int type;
        public int softInputMode;
        public float alpha = 1.0f;
        public float dimAmount = 1.0f;
        public float screenBrightness = -1.0f;
        public int windowAnimations;
        public int systemUiVisibility;

        public LayoutParams() { super(MATCH_PARENT, MATCH_PARENT); }

        public LayoutParams(int w, int h) { super(w, h); }

        public LayoutParams(int w, int h, int type, int flags, int format) { super(w, h); }

        public LayoutParams(ViewGroup.LayoutParams source) { super(source); }

        public String debug(String output) { return output; }

        public String toString() { return "WindowManager.LayoutParams"; }
    }
}
