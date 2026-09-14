package com.smide.plugins.java.run;

import com.smide.api.Ide;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.ProcessSpec;
import com.smide.api.execution.RunConfiguration;
import com.smide.api.execution.RunConfigurationType;
import com.smide.api.workspace.Workspace;
import com.smide.plugins.java.JavaProjectInfo;
import com.smide.plugins.java.JavaProjectRegistry;
import com.smide.plugins.java.JavaTools;
import javafx.scene.Node;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Runs tests through Surefire ({@code mvn test -Dtest=...}) or Gradle ({@code test --tests}). */
public final class JUnitRunType implements RunConfigurationType {

    public static final String ID = "java.junit";

    private final Ide ide;
    private final JavaProjectRegistry registry;

    public JUnitRunType(Ide ide, JavaProjectRegistry registry) {
        this.ide = ide;
        this.registry = registry;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "JUnit";
    }

    @Override
    public String iconLiteral() {
        return "fth-check-square";
    }

    @Override
    public RunConfiguration create(Workspace workspace) {
        return new Config(workspace);
    }

    @Override
    public List<RunConfiguration> detect(Workspace workspace) {
        Optional<JavaProjectInfo> info = registry.get(workspace);
        if (info.isEmpty()) {
            return List.of();
        }
        List<RunConfiguration> out = new ArrayList<>();
        if (!info.get().testClasses().isEmpty()) {
            Config all = new Config(workspace);
            all.setName("All tests");
            all.temporary();
            out.add(all);
        }
        for (JavaProjectInfo.RunnableClass rc : info.get().testClasses()) {
            Config c = new Config(workspace);
            c.setName(rc.simpleName());
            c.set("testClass", rc.fqn());
            c.set("module", Forms.relative(workspace.root(), rc.moduleRoot()));
            c.temporary();
            out.add(c);
        }
        return out;
    }

    @Override
    public Node editor(RunConfiguration configuration) {
        Config c = (Config) configuration;
        GridPane grid = Forms.grid();
        List<String> tests = registry.get(c.workspace()).map(i -> i.testClasses().stream()
                .map(JavaProjectInfo.RunnableClass::fqn).toList()).orElse(List.of());
        Forms.combo(grid, 0, "Test class (blank = all)", c, "testClass", tests);
        Forms.text(grid, 1, "Method (optional)", c, "method", "");
        Forms.text(grid, 2, "Module (relative)", c, "module", "root module");
        Forms.text(grid, 3, "Extra arguments", c, "extra", "-Dspring.profiles.active=test");
        return new VBox(8, grid, Forms.note("Maven: mvn test -Dtest=Class#method. Gradle: gradle test --tests Class.method."));
    }

    private final class Config extends BaseRunConfiguration {
        Config(Workspace workspace) {
            super(JUnitRunType.this, workspace);
        }

        @Override
        public ProcessSpec prepare(Ide ide, ExecutionMode mode) {
            JavaProjectInfo info = registry.get(workspace).orElse(null);
            String testClass = get("testClass", "");
            String method = get("method", "");
            List<String> cmd;
            MavenLayout maven = null;
            if (info != null && info.isGradle()) {
                cmd = JavaTools.gradle(ide, workspace.root());
                cmd.add("test");
                if (!testClass.isBlank()) {
                    cmd.add("--tests");
                    cmd.add(method.isBlank() ? testClass : testClass + "." + method);
                }
            } else {
                maven = MavenLayout.of(workspace.root(), moduleDir());
                cmd = maven.command(ide);
                cmd.add("-B");
                cmd.add("test");
                if (!testClass.isBlank()) {
                    cmd.add("-Dtest=" + testClass + (method.isBlank() ? "" : "#" + method));
                    cmd.add("-Dsurefire.failIfNoSpecifiedTests=false");
                }
            }
            cmd.addAll(Forms.splitArgs(get("extra", "")));
            return new ProcessSpec(name(), cmd, maven == null ? workspace.root() : maven.directory(),
                    JavaTools.environment(JavaTools.launchJdk(ide, workspace, registry).home()));
        }
    }
}
