package android.content;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Stub of android.content.SharedPreferences. The interface itself is modelled after the
 * real API; the no-op implementation below it is package-private so application code
 * cannot name or depend on it.
 */
public interface SharedPreferences {

    String getString(String key, String defValue);

    Set<String> getStringSet(String key, Set<String> defValues);

    int getInt(String key, int defValue);

    long getLong(String key, long defValue);

    float getFloat(String key, float defValue);

    boolean getBoolean(String key, boolean defValue);

    boolean contains(String key);

    Editor edit();

    Map<String, ?> getAll();

    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);

    void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);

    interface Editor {
        Editor putString(String key, String value);
        Editor putStringSet(String key, Set<String> values);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putFloat(String key, float value);
        Editor putBoolean(String key, boolean value);
        Editor remove(String key);
        Editor clear();
        boolean commit();
        void apply();
    }

    interface OnSharedPreferenceChangeListener {
        void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key);
    }
}

/** Stub backing store. Never used at runtime: nothing in this harness executes. */
class StubSharedPreferences implements SharedPreferences {

    private final Map<String, Object> values = new HashMap<String, Object>();

    public String getString(String key, String defValue) {
        Object v = values.get(key);
        return (v instanceof String) ? (String) v : defValue;
    }

    public Set<String> getStringSet(String key, Set<String> defValues) {
        Object v = values.get(key);
        if (v instanceof Set) return castSet(v);
        return defValues;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> castSet(Object v) { return (Set<String>) v; }

    public int getInt(String key, int defValue) {
        Object v = values.get(key);
        return (v instanceof Integer) ? ((Integer) v).intValue() : defValue;
    }

    public long getLong(String key, long defValue) {
        Object v = values.get(key);
        return (v instanceof Long) ? ((Long) v).longValue() : defValue;
    }

    public float getFloat(String key, float defValue) {
        Object v = values.get(key);
        return (v instanceof Float) ? ((Float) v).floatValue() : defValue;
    }

    public boolean getBoolean(String key, boolean defValue) {
        Object v = values.get(key);
        return (v instanceof Boolean) ? ((Boolean) v).booleanValue() : defValue;
    }

    public boolean contains(String key) { return values.containsKey(key); }

    public Map<String, ?> getAll() { return new HashMap<String, Object>(values); }

    public Editor edit() { return new StubEditor(values); }

    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }

    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
}

/** Editor stub: applies changes to the in-memory map and otherwise does nothing. */
class StubEditor implements SharedPreferences.Editor {

    private final Map<String, Object> values;

    StubEditor(Map<String, Object> values) { this.values = values; }

    public SharedPreferences.Editor putString(String key, String value) { values.put(key, value); return this; }

    public SharedPreferences.Editor putStringSet(String key, Set<String> value) { values.put(key, value); return this; }

    public SharedPreferences.Editor putInt(String key, int value) { values.put(key, Integer.valueOf(value)); return this; }

    public SharedPreferences.Editor putLong(String key, long value) { values.put(key, Long.valueOf(value)); return this; }

    public SharedPreferences.Editor putFloat(String key, float value) { values.put(key, Float.valueOf(value)); return this; }

    public SharedPreferences.Editor putBoolean(String key, boolean value) { values.put(key, Boolean.valueOf(value)); return this; }

    public SharedPreferences.Editor remove(String key) { values.remove(key); return this; }

    public SharedPreferences.Editor clear() { values.clear(); return this; }

    public boolean commit() { return true; }

    public void apply() { }
}
