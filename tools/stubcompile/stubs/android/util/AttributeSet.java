package android.util;

/** Stub of android.util.AttributeSet (only needed so widget constructors can be overloaded). */
public interface AttributeSet {

    int getAttributeCount();

    String getAttributeName(int index);

    String getAttributeValue(int index);

    String getAttributeValue(String namespace, String name);

    int getAttributeIntValue(String namespace, String attribute, int defaultValue);

    boolean getAttributeBooleanValue(String namespace, String attribute, boolean defaultValue);

    float getAttributeFloatValue(String namespace, String attribute, float defaultValue);

    int getAttributeResourceValue(String namespace, String attribute, int defaultValue);

    String getPositionDescription();
}
