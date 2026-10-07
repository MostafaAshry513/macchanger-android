package android.widget;

import android.content.Context;
import android.util.AttributeSet;

/** Stub of android.widget.ProgressBar. */
public class ProgressBar extends android.view.View {

    public ProgressBar(Context context) { super(context); }

    public ProgressBar(Context context, AttributeSet attrs) { super(context, attrs); }

    public void setProgress(int progress) { }

    public int getProgress() { return 0; }

    public void setIndeterminate(boolean indeterminate) { }

    public void setVisibility(int visibility) { super.setVisibility(visibility); }
}
