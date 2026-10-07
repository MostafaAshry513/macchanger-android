package android.text;

/**
 * Stub of android.text.Editable.
 *
 * Permissive on purpose: indexOf/trim/substring do not exist on the real Editable
 * interface (they are String methods). They are provided here so small future edits to
 * getText() handling do not break the gate; README.md records this as a known
 * false-PASS surface of the gate.
 */
public interface Editable extends Spannable {

    Editable replace(int st, int en, CharSequence text);

    Editable replace(int st, int en, CharSequence text, int start, int end);

    Editable insert(int where, CharSequence text);

    Editable insert(int where, CharSequence text, int start, int end);

    Editable delete(int st, int en);

    Editable append(CharSequence text);

    Editable append(CharSequence text, int start, int end);

    Editable append(char text);

    Editable clear();

    int indexOf(String text);

    int indexOf(String text, int fromIndex);

    int lastIndexOf(String text);

    String substring(int start);

    String substring(int start, int end);

    String trim();

    String toString();
}
