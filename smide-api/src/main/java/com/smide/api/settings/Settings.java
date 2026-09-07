package com.smide.api.settings;

import java.util.List;
import java.util.function.Consumer;

/**
 * Key/value settings, application-wide or per workspace. Keys are dotted names such as
 * {@code editor.fontSize}. Writes are persisted promptly; reads are cheap.
 */
public interface Settings {

    String get(String key, String defaultValue);

    int getInt(String key, int defaultValue);

    boolean getBoolean(String key, boolean defaultValue);

    double getDouble(String key, double defaultValue);

    List<String> getList(String key);

    void set(String key, String value);

    void setInt(String key, int value);

    void setBoolean(String key, boolean value);

    void setDouble(String key, double value);

    void setList(String key, List<String> values);

    void remove(String key);

    /** Called with the key whenever a value changes. */
    void addListener(Consumer<String> listener);
}
