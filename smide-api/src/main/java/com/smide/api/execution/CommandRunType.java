package com.smide.api.execution;

import com.smide.api.Ide;
import com.smide.api.workspace.Workspace;
import javafx.scene.Node;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A run configuration type made of fields and a command - what most languages need.
 *
 * <p>A plugin says which fields its form has, how to turn a configuration into a command,
 * and what it can find in a project by looking; saving, the form, the environment and the
 * working directory are the same for every language and are done here. Written once so a
 * new language gets run configurations for the price of those three things.
 */
public abstract class CommandRunType implements RunConfigurationType {

    /** One field of the form: a text box, or a choice when it has options. */
    public record Field(String key, String label, String prompt, List<String> options) {

        public static Field text(String key, String label, String prompt) {
            return new Field(key, label, prompt, List.of());
        }

        public static Field choice(String key, String label, List<String> options) {
            return new Field(key, label, "", options);
        }
    }

    /**
     * What to run, and where.
     *
     * @param directory   where to run it, unless the configuration sets its own; null for the project root
     * @param environment variables the language needs; the configuration's own are laid over them
     */
    public record Command(List<String> command, Path directory, Map<String, String> environment) {

        public Command(List<String> command, Path directory) {
            this(command, directory, Map.of());
        }
    }

    /** A configuration found by looking at the project: its name, and its fields. */
    public record Detected(String name, Map<String, String> values) {
    }

    protected final Ide ide;
    private final String id;
    private final String displayName;
    private final String icon;

    protected CommandRunType(Ide ide, String id, String displayName, String icon) {
        this.ide = ide;
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final String displayName() {
        return displayName;
    }

    @Override
    public final String iconLiteral() {
        return icon;
    }

    /** The fields the form shows, besides the environment and working directory every configuration has. */
    protected abstract List<Field> fields();

    /** The command for a configuration. Throws with a message the reader can act on. */
    protected abstract Command command(Config configuration, ExecutionMode mode) throws Exception;

    /** Field values a new configuration starts with. */
    protected Map<String, String> defaults() {
        return Map.of();
    }

    /** A line under the form saying how it runs, or null. */
    protected String note() {
        return null;
    }

    /** Configurations found by looking at the project. */
    protected List<Detected> find(Workspace workspace) {
        return List.of();
    }

    @Override
    public RunConfiguration create(Workspace workspace) {
        Config c = new Config(workspace);
        c.setName(displayName);
        defaults().forEach(c::set);
        return c;
    }

    @Override
    public final List<RunConfiguration> detect(Workspace workspace) {
        List<RunConfiguration> out = new ArrayList<>();
        for (Detected detected : find(workspace)) {
            Config c = new Config(workspace);
            c.setName(detected.name());
            defaults().forEach(c::set);
            detected.values().forEach(c::set);
            out.add(c.temporary());
        }
        return out;
    }

    @Override
    public Node editor(RunConfiguration configuration) {
        Config c = (Config) configuration;
        GridPane grid = Forms.grid();
        int row = 0;
        for (Field field : fields()) {
            if (field.options().isEmpty()) {
                Forms.text(grid, row++, field.label(), c, field.key(), field.prompt());
            } else {
                Forms.combo(grid, row++, field.label(), c, field.key(), field.options());
            }
        }
        Forms.text(grid, row++, "Environment", c, "env", "KEY=value;OTHER=value");
        Forms.directory(grid, row, "Working directory", c, "workingDir", ide);
        return note() == null ? new VBox(8, grid) : new VBox(8, grid, Forms.note(note()));
    }

    /** A configuration of this type: its fields, run through {@link #command}. */
    public final class Config extends BaseRunConfiguration {

        Config(Workspace workspace) {
            super(CommandRunType.this, workspace);
        }

        @Override
        public ProcessSpec prepare(Ide ide, ExecutionMode mode) throws Exception {
            Command command = command(this, mode);
            String wd = get("workingDir", "");
            Path cwd = !wd.isBlank() ? Path.of(wd)
                    : command.directory() == null ? workspace.root() : command.directory();
            Map<String, String> env = new LinkedHashMap<>(command.environment());
            env.putAll(Forms.environment(get("env", "")));
            return new ProcessSpec(name(), command.command(), cwd, env);
        }
    }
}
