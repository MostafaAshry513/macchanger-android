package android.text;

/** Stub of android.text.SpannableStringBuilder. Spans are accepted and discarded. */
public class SpannableStringBuilder implements Editable, Appendable, CharSequence {

    private final StringBuilder b = new StringBuilder();

    public SpannableStringBuilder() { }

    public SpannableStringBuilder(CharSequence text) { if (text != null) b.append(text); }

    public SpannableStringBuilder(CharSequence text, int start, int end) {
        if (text != null) b.append(text, start, end);
    }

    public int length() { return b.length(); }

    public char charAt(int index) { return b.charAt(index); }

    public CharSequence subSequence(int start, int end) { return b.subSequence(start, end); }

    public SpannableStringBuilder append(CharSequence text) { b.append(text); return this; }

    public SpannableStringBuilder append(CharSequence text, int start, int end) {
        b.append(text, start, end);
        return this;
    }

    public SpannableStringBuilder append(char text) { b.append(text); return this; }

    public SpannableStringBuilder replace(int st, int en, CharSequence text) {
        b.replace(st, en, text == null ? "" : text.toString());
        return this;
    }

    public SpannableStringBuilder replace(int st, int en, CharSequence text, int start, int end) {
        return replace(st, en, text);
    }

    public SpannableStringBuilder insert(int where, CharSequence text) {
        b.insert(where, text == null ? "" : text.toString());
        return this;
    }

    public SpannableStringBuilder insert(int where, CharSequence text, int start, int end) {
        return insert(where, text);
    }

    public SpannableStringBuilder delete(int st, int en) { b.delete(st, en); return this; }

    public SpannableStringBuilder clear() { b.setLength(0); return this; }

    public void setSpan(Object what, int start, int end, int flags) { }

    public void removeSpan(Object what) { }

    public <T> T[] getSpans(int start, int end, Class<T> type) { return null; }

    public int getSpanStart(Object tag) { return -1; }

    public int getSpanEnd(Object tag) { return -1; }

    public int getSpanFlags(Object tag) { return 0; }

    public int nextSpanTransition(int start, int limit, Class type) { return limit; }

    public int indexOf(String text) { return b.indexOf(text); }

    public int indexOf(String text, int fromIndex) { return b.indexOf(text, fromIndex); }

    public int lastIndexOf(String text) { return b.lastIndexOf(text); }

    public String substring(int start) { return b.substring(start); }

    public String substring(int start, int end) { return b.substring(start, end); }

    public String trim() { return b.toString().trim(); }

    public String toString() { return b.toString(); }
}
