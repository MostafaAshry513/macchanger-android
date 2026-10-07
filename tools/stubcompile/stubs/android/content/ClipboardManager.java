package android.content;

/** Stub of android.content.ClipboardManager. Clipboard access needs no manifest permission. */
public class ClipboardManager extends android.text.ClipboardManager {

    public void setPrimaryClip(ClipData clip) { }

    public ClipData getPrimaryClip() { return null; }

    public boolean hasPrimaryClip() { return false; }

    public CharSequence getText() { return null; }

    public void setText(CharSequence text) { }

    public boolean hasText() { return false; }

    public void addPrimaryClipChangedListener(OnPrimaryClipChangedListener listener) { }

    public void removePrimaryClipChangedListener(OnPrimaryClipChangedListener listener) { }

    public interface OnPrimaryClipChangedListener {
        void onPrimaryClipChanged();
    }
}
