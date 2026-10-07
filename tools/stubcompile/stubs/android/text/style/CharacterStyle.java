package android.text.style;

import android.text.TextPaint;

/** Stub of android.text.style.CharacterStyle. */
public abstract class CharacterStyle {

    public CharacterStyle() { }

    public abstract void updateDrawState(TextPaint tp);

    public CharacterStyle getUnderlying() { return this; }
}
