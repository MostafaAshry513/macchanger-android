package android.text;

/** Stub of android.text.TextUtils, including the TruncateAt enum used by setEllipsize. */
public class TextUtils {

    private TextUtils() { }

    public enum TruncateAt { START, MIDDLE, END, MARQUEE }

    public static boolean isEmpty(CharSequence str) { return str == null || str.length() == 0; }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        int n = a.length();
        if (n != b.length()) return false;
        for (int i = 0; i < n; i++) if (a.charAt(i) != b.charAt(i)) return false;
        return true;
    }

    public static int getTrimmedLength(CharSequence s) { return s == null ? 0 : s.toString().trim().length(); }

    public static boolean isDigitsOnly(CharSequence str) { return false; }

    public static boolean isGraphic(CharSequence str) { return false; }

    public static CharSequence concat(CharSequence... text) { return ""; }

    public static String join(CharSequence delimiter, Iterable<?> tokens) { return ""; }

    public static String join(CharSequence delimiter, Object[] tokens) { return ""; }

    public static CharSequence ellipsize(CharSequence text, TextPaint p, float avail, TruncateAt where) { return text; }

    public static CharSequence expandTemplate(CharSequence template, CharSequence... values) { return template; }
}
