package com.smide.plugins.java.run;

import com.smide.api.execution.RunConfiguration;
import com.smide.api.execution.RunConfigurationType;
import com.smide.api.workspace.Workspace;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Name, type, workspace and a string map of fields; subclasses add {@code prepare}. */
public abstract class BaseRunConfiguration implements RunConfiguration {

    protected final RunConfigurationType type;
    protected final Workspace workspace;
    protected final Map<String, String> values = new LinkedHashMap<>();
    private String name = "Unnamed";
    private boolean temporary;

    protected BaseRunConfiguration(RunConfigurationType type, Workspace workspace) {
        this.type = type;
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name == null || name.isBlank() ? "Unnamed" : name;
    }

    @Override
    public RunConfigurationType type() {
        return type;
    }

    @Override
    public Workspace workspace() {
        return workspace;
    }

    @Override
    public Map<String, String> toMap() {
        return new LinkedHashMap<>(values);
    }

    @Override
    public void fromMap(Map<String, String> map) {
        values.clear();
        values.putAll(map);
    }

    @Override
    public boolean isTemporary() {
        return temporary;
    }

    public BaseRunConfiguration temporary() {
        this.temporary = true;
        return this;
    }

    public String get(String key, String defaultValue) {
        String v = values.get(key);
        return v == null || v.isBlank() ? defaultValue : v;
    }

    public void set(String key, String value) {
        values.put(key, value == null ? "" : value);
    }

    public boolean flag(String key, boolean defaultValue) {
        String v = values.get(key);
        return v == null || v.isBlank() ? defaultValue : Boolean.parseBoolean(v);
    }

    /** The module directory the configuration runs in: its setting, or the workspace root. */
    public Path moduleDir() {
        String dir = get("module", "");
        if (!dir.isBlank()) {
            Path p = Path.of(dir);
            return p.isAbsolute() ? p : workspace.root().resolve(p);
        }
        return workspace.root();
    }
}
