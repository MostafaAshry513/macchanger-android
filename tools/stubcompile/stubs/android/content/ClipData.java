package android.content;

import android.net.Uri;

/** Stub of android.content.ClipData (used by the "copy log" action). */
public class ClipData {

    public static ClipData newPlainText(CharSequence label, CharSequence text) { return new ClipData(); }

    public static ClipData newHtmlText(CharSequence label, CharSequence text, String htmlText) { return new ClipData(); }

    public static ClipData newIntent(CharSequence label, Intent intent) { return new ClipData(); }

    public static ClipData newRawUri(CharSequence label, Uri uri) { return new ClipData(); }

    public int getItemCount() { return 0; }

    public Item getItemAt(int index) { return null; }

    public CharSequence getLabel() { return null; }

    public static class Item {

        public Item(CharSequence text) { }

        public Item(CharSequence text, String htmlText) { }

        public CharSequence getText() { return null; }

        public String getHtmlText() { return null; }

        public CharSequence coerceToText(Context context) { return null; }
    }
}
