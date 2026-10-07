package android.content;

/** Stub of android.content.DialogInterface. */
public interface DialogInterface {

    int BUTTON_POSITIVE = -1;
    int BUTTON_NEGATIVE = -2;
    int BUTTON_NEUTRAL = -3;
    int BUTTON1 = BUTTON_POSITIVE;
    int BUTTON2 = BUTTON_NEGATIVE;
    int BUTTON3 = BUTTON_NEUTRAL;

    void cancel();

    void dismiss();

    interface OnClickListener { void onClick(DialogInterface dialog, int which); }

    interface OnDismissListener { void onDismiss(DialogInterface dialog); }

    interface OnCancelListener { void onCancel(DialogInterface dialog); }

    interface OnShowListener { void onShow(DialogInterface dialog); }

    interface OnMultiChoiceClickListener { void onClick(DialogInterface dialog, int which, boolean isChecked); }
}
