package android.os;

import java.util.ArrayList;
import java.util.Set;

/** Stub of android.os.Bundle. */
public class Bundle {

    public Bundle() { }

    public Bundle(Bundle b) { }

    public int size() { return 0; }

    public boolean isEmpty() { return true; }

    public void clear() { }

    public boolean containsKey(String key) { return false; }

    public Set<String> keySet() { return null; }

    public void remove(String key) { }

    public void putString(String key, String value) { }

    public String getString(String key) { return null; }

    public String getString(String key, String defaultValue) { return defaultValue; }

    public void putCharSequence(String key, CharSequence value) { }

    public CharSequence getCharSequence(String key) { return null; }

    public void putInt(String key, int value) { }

    public int getInt(String key) { return 0; }

    public int getInt(String key, int defaultValue) { return defaultValue; }

    public void putLong(String key, long value) { }

    public long getLong(String key) { return 0L; }

    public long getLong(String key, long defaultValue) { return defaultValue; }

    public void putFloat(String key, float value) { }

    public float getFloat(String key, float defaultValue) { return defaultValue; }

    public void putBoolean(String key, boolean value) { }

    public boolean getBoolean(String key) { return false; }

    public boolean getBoolean(String key, boolean defaultValue) { return defaultValue; }

    public void putByteArray(String key, byte[] value) { }

    public byte[] getByteArray(String key) { return null; }

    public void putStringArrayList(String key, ArrayList<String> value) { }

    public ArrayList<String> getStringArrayList(String key) { return null; }

    public void putAll(Bundle map) { }

    public String toString() { return "Bundle"; }
}
