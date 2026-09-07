package com.smide.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.smide.api.settings.Settings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A flat key/value store in one JSON file, written on every change.
 *
 * <p>Writes are whole-file, atomic (write to a sibling, then move) and immediate rather
 * than debounced: settings change at human speed, and a crash between a change and its
 * write is the one case worth paying for.
 */
public final class JsonSettings implements Settings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Map<String, String> values = new TreeMap<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    /** An in-memory store, for staging in the Settings dialog and for tests. */
    public JsonSettings() {
        this.file = null;
    }

    public JsonSettings(Path file) {
        this.file = file;
        load();
    }

    public Path file() {
        return file;
    }

    private void load() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root != null && root.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                    JsonElement v = e.getValue();
                    if (v.isJsonPrimitive()) {
                        values.put(e.getKey(), v.getAsString());
                    } else if (v.isJsonArray()) {
                        values.put(e.getKey(), v.toString());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("smIDE: cannot read " + file + ": " + e);
        }
    }

    private synchronized void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<String, String> e : values.entrySet()) {
                String v = e.getValue();
                if (v.startsWith("[") && v.endsWith("]")) {
                    try {
                        root.add(e.getKey(), JsonParser.parseString(v));
                        continue;
                    } catch (RuntimeException ignored) {
                        // Not a list after all.
                    }
                }
                root.addProperty(e.getKey(), v);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            System.err.println("smIDE: cannot write " + file + ": " + e);
        }
    }

    /** Every key present, for the Settings dialog to stage and for import/export. */
    public synchronized Map<String, String> snapshot() {
        return new TreeMap<>(values);
    }

    public synchronized void putAll(Map<String, String> other) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : other.entrySet()) {
            String old = values.put(e.getKey(), e.getValue());
            if (old == null || !old.equals(e.getValue())) {
                changed.add(e.getKey());
            }
        }
        if (!changed.isEmpty()) {
            save();
            changed.forEach(this::fire);
        }
    }

    @Override
    public synchronized String get(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    @Override
    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)).strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    @Override
    public double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(get(key, String.valueOf(defaultValue)).strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public List<String> getList(String key) {
        String raw = get(key, null);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonElement el = JsonParser.parseString(raw);
            if (el.isJsonArray()) {
                List<String> out = new ArrayList<>();
                el.getAsJsonArray().forEach(e -> out.add(e.getAsString()));
                return out;
            }
        } catch (RuntimeException ignored) {
            // Fall through.
        }
        return List.of(raw);
    }

    @Override
    public void set(String key, String value) {
        boolean changed;
        synchronized (this) {
            String old = value == null ? values.remove(key) : values.put(key, value);
            changed = old == null ? value != null : !old.equals(value);
            if (changed) {
                save();
            }
        }
        if (changed) {
            fire(key);
        }
    }

    @Override
    public void setInt(String key, int value) {
        set(key, String.valueOf(value));
    }

    @Override
    public void setBoolean(String key, boolean value) {
        set(key, String.valueOf(value));
    }

    @Override
    public void setDouble(String key, double value) {
        set(key, String.valueOf(value));
    }

    @Override
    public void setList(String key, List<String> list) {
        set(key, GSON.toJson(list));
    }

    @Override
    public void remove(String key) {
        set(key, null);
    }

    @Override
    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    private void fire(String key) {
        for (Consumer<String> l : listeners) {
            try {
                l.accept(key);
            } catch (RuntimeException e) {
                System.err.println("smIDE: settings listener failed: " + e);
            }
        }
    }
}
