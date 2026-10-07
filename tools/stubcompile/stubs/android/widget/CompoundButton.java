package android.widget;

import android.content.Context;
import android.util.AttributeSet;

/** Stub of android.widget.CompoundButton. */
public abstract class CompoundButton extends Button {

    public CompoundButton(Context context) { super(context); }

    public CompoundButton(Context context, AttributeSet attrs) { super(context, attrs); }

    public CompoundButton(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    public void setChecked(boolean checked) { }

    public boolean isChecked() { return false; }

    public void toggle() { }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) { }

    public void setButtonDrawable(android.graphics.drawable.Drawable d) { }

    public interface OnCheckedChangeListener {
        void onCheckedChanged(CompoundButton buttonView, boolean isChecked);
    }
}
