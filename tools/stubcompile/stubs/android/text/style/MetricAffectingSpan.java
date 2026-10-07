package android.text.style;

import android.text.TextPaint;

/** Stub of android.text.style.MetricAffectingSpan. */
public abstract class MetricAffectingSpan extends CharacterStyle {

    public MetricAffectingSpan() { }

    public abstract void updateMeasureState(TextPaint p);
}
