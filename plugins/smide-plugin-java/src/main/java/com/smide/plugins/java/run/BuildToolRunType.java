package com.smide.plugins.java.run;

import com.smide.api.Ide;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.ProcessSpec;
import com.smide.api.execution.RunConfiguration;
import com.smide.api.execution.RunConfigurationType;
import com.smide.api.workspace.Workspace;
import com.smide.plugins.java.JavaTools;
import javafx.scene.Node;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;

/** A Maven goal line or a Gradle task line, run as typed. One class serves both tools. */
public final class BuildToolRunType implements RunConfigurationType {

    private final Ide ide;
    private final boolean gradle;

    public BuildToolRunType(Ide ide, boolean gradle) {
        this.ide = ide;
        this.gradle = gradle;
    }

    @Override
    public String id() {
        return gradle ? "java.gradle" : "java.maven";
    }

    @Override
    public String displayName() {
        return gradle ? "Gradle Task" : "Maven Goal";
    }

    @Override
    public String iconLiteral() {
        return gradle ? "mdi2e-elephant" : "fth-package";
    }

    @Override
    public RunConfiguration create(Workspace workspace) {
        Config c = new Config(workspace);
        c.set("goals", gradle ? "build" : "clean package");
        return c;
    }

    @Override
    public Node editor(RunConfiguration configuration) {
        Config c = (Config) configuration;
        GridPane grid = Forms.grid();
        Forms.text(grid, 0, gradle ? "Tasks" : "Goals", c, "goals", gradle ? "clean build" : "clean package");
        Forms.text(grid, 1, gradle ? "Options" : "Profiles (comma)", c, "profiles", gradle ? "--offline" : "prod");
        Forms.text(grid, 2, "Extra arguments", c, "extra", "-DskipTests");
        Forms.directory(grid, 3, "Directory", c, "workingDir", ide);
        return new VBox(8, grid);
    }

    private final class Config extends BaseRunConfiguration {
        Config(Workspace workspace) {
            super(BuildToolRunType.this, workspace);
        }

        @Override
        public ProcessSpec prepare(Ide ide, ExecutionMode mode) {
            String wd = get("workingDir", "");
            // Not the workspace root: a repository often keeps its build a level down.
            Path cwd = wd.isBlank() ? MavenLayout.buildRootFor(workspace.root()) : Path.of(wd);
            List<String> cmd = gradle ? JavaTools.gradle(ide, cwd) : JavaTools.maven(ide, cwd);
            if (!gradle) {
                cmd.add("-B");
            }
            cmd.addAll(Forms.splitArgs(get("goals", "")));
            String profiles = get("profiles", "");
            if (!profiles.isBlank()) {
                if (gradle) {
                    cmd.addAll(Forms.splitArgs(profiles));
                } else {
                    cmd.add("-P" + profiles.replace(" ", ""));
                }
            }
            cmd.addAll(Forms.splitArgs(get("extra", "")));
            return new ProcessSpec(name(), cmd, cwd);
        }
    }
}
