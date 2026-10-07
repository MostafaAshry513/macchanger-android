package android.widget;

import android.content.Context;
import android.text.Editable;
import android.util.AttributeSet;
import android.view.KeyEvent;

/** Stub of android.widget.EditText. getText() covariantly returns Editable, as on a device. */
public class EditText extends TextView {

    public EditText(Context context) { super(context); }

    public EditText(Context context, AttributeSet attrs) { super(context, attrs); }

    public EditText(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    public Editable getText() { return new android.text.SpannableStringBuilder(); }

    public void setSelection(int index) { }

    public void setSelection(int start, int stop) { }

    public void selectAll() { }

    public int getSelectionStart() { return 0; }

    public int getSelectionEnd() { return 0; }

    public void setError(CharSequence error) { }

    public void setError(CharSequence error, android.graphics.drawable.Drawable icon) { }

    public CharSequence getError() { return null; }

    public void extendSelection(int index) { }

    public void setOnEditorActionListener(OnEditorActionListener l) { }

    public interface OnEditorActionListener {
        boolean onEditorAction(TextView v, int actionId, KeyEvent event);
    }
}
