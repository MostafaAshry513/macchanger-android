package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

/** Stub of android.app.AlertDialog, including the Builder the app may use for confirmations. */
public class AlertDialog implements DialogInterface {

    protected AlertDialog() { }

    public void show() { }

    public void dismiss() { }

    public void cancel() { }

    public boolean isShowing() { return false; }

    public void setTitle(CharSequence title) { }

    public void setTitle(int titleId) { }

    public void setMessage(CharSequence message) { }

    public void setView(View view) { }

    public void setCancelable(boolean flag) { }

    public void setCanceledOnTouchOutside(boolean cancel) { }

    public void setOnDismissListener(DialogInterface.OnDismissListener listener) { }

    public void setOnCancelListener(DialogInterface.OnCancelListener listener) { }

    public void setOnShowListener(DialogInterface.OnShowListener listener) { }

    public View findViewById(int id) { return null; }

    public static class Builder {

        public Builder(Context context) { }

        public Context getContext() { return null; }

        public Builder setTitle(CharSequence title) { return this; }

        public Builder setTitle(int titleId) { return this; }

        public Builder setMessage(CharSequence message) { return this; }

        public Builder setMessage(int messageId) { return this; }

        public Builder setView(View view) { return this; }

        public Builder setCancelable(boolean cancelable) { return this; }

        public Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) { return this; }

        public Builder setPositiveButton(int textId, DialogInterface.OnClickListener listener) { return this; }

        public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) { return this; }

        public Builder setNegativeButton(int textId, DialogInterface.OnClickListener listener) { return this; }

        public Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) { return this; }

        public Builder setNeutralButton(int textId, DialogInterface.OnClickListener listener) { return this; }

        public Builder setOnDismissListener(DialogInterface.OnDismissListener listener) { return this; }

        public AlertDialog create() { return new AlertDialog(); }

        public AlertDialog show() { AlertDialog d = create(); d.show(); return d; }
    }
}
