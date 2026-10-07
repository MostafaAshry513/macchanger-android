package android.text;

/** Stub of android.text.Spannable. */
public interface Spannable extends Spanned {

    void setSpan(Object what, int start, int end, int flags);

    void removeSpan(Object what);
}
