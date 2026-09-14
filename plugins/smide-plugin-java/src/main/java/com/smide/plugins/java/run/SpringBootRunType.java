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

/** {@code spring-boot:run} / {@code bootRun} with profiles, JVM arguments and program arguments. */
public final class SpringBootRunType implements RunConfigurationType {

    public static final String ID = "java.springboot";

    private final Ide ide;
    private final JavaProjectRegistry registry;

    public SpringBootRunType(Ide ide, JavaProjectRegistry registry) {
        this.ide = ide;
        this.registry = registry;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Spring Boot";
    }

    @Override
    public String iconLiteral() {
        return "mdi2l-leaf";
    }

    @Override
    public boolean supportsDebug() {
        return true;
    }

    @Override
    public RunConfiguration create(Workspace workspace) {
        return new Config(workspace);
    }

    @Override
    public List<RunConfiguration> detect(Workspace workspace) {
        Optional<JavaProjectInfo> info = registry.get(workspace);
        if (info.isEmpty() || !info.get().springBoot()) {
            return List.of();
        }
        List<RunConfiguration> out = new ArrayList<>();
        // One per module that has a main class, since a multi-module build usually has one app.
        List<JavaProjectInfo.RunnableClass> mains = info.get().mainClasses();
        java.util.Set<java.nio.file.Path> seen = new java.util.HashSet<>();
        for (JavaProjectInfo.RunnableClass rc : mains) {
            if (!seen.add(rc.moduleRoot())) {
                continue;
            }
            Config c = new Config(workspace);
            String moduleName = rc.moduleRoot().getFileName() == null ? workspace.name() : rc.moduleRoot().getFileName().toString();
            c.setName(mains.size() > 1 ? moduleName + " (Spring Boot)" : rc.simpleName() + " (Spring Boot)");
            c.set("module", Forms.relative(workspace.root(), rc.moduleRoot()));
            c.set("mainClass", rc.fqn());
            c.temporary();
            out.add(c);
        }
        if (out.isEmpty()) {
            Config c = new Config(workspace);
            c.setName(workspace.name() + " (Spring Boot)");
            c.temporary();
            out.add(c);
        }
        return out;
    }

    @Override
    public Node editor(RunConfiguration configuration) {
        Config c = (Config) configuration;
        GridPane grid = Forms.grid();
        Forms.text(grid, 0, "Active profiles", c, "profiles", "dev,local");
        Forms.text(grid, 1, "Program arguments", c, "args", "--server.port=8081");
        Forms.text(grid, 2, "JVM arguments", c, "vmArgs", "-Xmx512m");
        Forms.text(grid, 3, "Main class (optional)", c, "mainClass", "");
        Forms.text(grid, 4, "Module (relative)", c, "module", "root module");
        Forms.text(grid, 5, "Environment (K=V;K=V)", c, "env", "SPRING_DATASOURCE_URL=jdbc:...");
        Forms.text(grid, 6, "Debug port", c, "debugPort", "5005");
        Forms.check(grid, 7, "Skip tests", c, "skipTests", true);
        return new VBox(8, grid, Forms.note("Maven: mvn spring-boot:run. Gradle: gradle bootRun."
                + " Profiles become --spring.profiles.active, environment variables are set on the"
                + " process, and Debug attaches over JDWP on the port above."));
    }

    private final class Config extends BaseRunConfiguration {
        Config(Workspace workspace) {
            super(SpringBootRunType.this, workspace);
        }

        @Override
        public ProcessSpec prepare(Ide ide, ExecutionMode mode) {
            JavaProjectInfo info = registry.get(workspace).orElse(null);
            String profiles = get("profiles", "");
            String args = get("args", "");
            String vmArgs = get("vmArgs", "");
            if (mode == ExecutionMode.DEBUG) {
                vmArgs = ("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:"
                        + get("debugPort", "5005") + " " + vmArgs).strip();
            }
            List<String> cmd;
            java.nio.file.Path cwd;
            if (info != null && info.isGradle()) {
                cmd = JavaTools.gradle(ide, workspace.root());
                String module = get("module", "");
                cmd.add(module.isBlank() ? "bootRun" : module.replace('/', ':').replace('\\', ':') + ":bootRun");
                if (!args.isBlank() || !profiles.isBlank()) {
                    String all = (profiles.isBlank() ? "" : "--spring.profiles.active=" + profiles + " ") + args;
                    cmd.add("--args=" + all.strip());
                }
                cwd = workspace.root();
            } else {
                // The module, or wherever the pom really is: the workspace root is not
                // always a Maven project, and spring-boot:run has to start in one.
                cwd = MavenLayout.of(workspace.root(), moduleDir()).directory();
                cmd = JavaTools.maven(ide, cwd);
                cmd.add("-B");
                if (flag("skipTests", true)) {
                    cmd.add("-DskipTests");
                }
                cmd.add("spring-boot:run");
                if (!profiles.isBlank()) {
                    cmd.add("-Dspring-boot.run.profiles=" + profiles);
                }
                if (!args.isBlank()) {
                    cmd.add("-Dspring-boot.run.arguments=" + args);
                }
                if (!vmArgs.isBlank()) {
                    cmd.add("-Dspring-boot.run.jvmArguments=" + vmArgs);
                }
                String mainClass = get("mainClass", "");
                if (!mainClass.isBlank()) {
                    cmd.add("-Dspring-boot.run.main-class=" + mainClass);
                }
            }
            // The build and the application it forks run on the project's JDK; the configuration's variables win.
            java.util.Map<String, String> env = new java.util.HashMap<>(
                    JavaTools.environment(JavaTools.launchJdk(ide, workspace, registry).home()));
            env.putAll(Forms.environment(get("env", "")));
            return new ProcessSpec(name(), cmd, cwd, env);
        }
    }
}
