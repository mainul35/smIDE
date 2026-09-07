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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs a main class: the module is compiled with Maven (or Gradle's classes are used as
 * they are), the runtime classpath resolved, and a plain {@code java} process started so
 * VM options are honoured and Stop ends only the application.
 */
public final class ApplicationRunType implements RunConfigurationType {

    public static final String ID = "java.application";

    private final Ide ide;
    private final JavaProjectRegistry registry;

    public ApplicationRunType(Ide ide, JavaProjectRegistry registry) {
        this.ide = ide;
        this.registry = registry;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Application";
    }

    @Override
    public String iconLiteral() {
        return "mdi2l-language-java";
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
        if (info.isEmpty()) {
            return List.of();
        }
        List<RunConfiguration> out = new ArrayList<>();
        for (JavaProjectInfo.RunnableClass rc : info.get().mainClasses()) {
            Config c = new Config(workspace);
            c.setName(rc.simpleName());
            c.set("mainClass", rc.fqn());
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
        List<String> mains = registry.get(c.workspace()).map(i -> i.mainClasses().stream()
                .map(JavaProjectInfo.RunnableClass::fqn).toList()).orElse(List.of());
        Forms.combo(grid, 0, "Main class", c, "mainClass", mains);
        Forms.text(grid, 1, "Program arguments", c, "args", "");
        Forms.text(grid, 2, "VM options", c, "vmArgs", "-Xmx512m");
        Forms.text(grid, 3, "Module (relative)", c, "module", "root module");
        Forms.directory(grid, 4, "Working directory", c, "workingDir", ide);
        Forms.text(grid, 5, "Environment (K=V;K=V)", c, "env", "");
        Forms.text(grid, 6, "Debug port", c, "debugPort", "5005");
        Forms.check(grid, 7, "Build before run", c, "build", true);
        return new VBox(8, grid, Forms.note("Debug starts the program with a JDWP agent on the port above and waits for the IDE to attach, so a breakpoint on the first line is honoured."));
    }

    private final class Config extends BaseRunConfiguration {
        Config(Workspace workspace) {
            super(ApplicationRunType.this, workspace);
        }

        @Override
        public ProcessSpec prepare(Ide ide, ExecutionMode mode) throws Exception {
            String mainClass = get("mainClass", "");
            if (mainClass.isBlank()) {
                throw new IllegalStateException("No main class set. Edit the configuration.");
            }
            Path root = workspace.root();
            Path module = moduleDir();
            JavaProjectInfo info = registry.get(workspace).orElse(null);
            String classpath;
            if (info != null && info.isMaven()) {
                classpath = MavenBuild.compileAndClasspath(ide, root, module, !flag("build", true));
            } else if (info != null && info.isGradle()) {
                if (flag("build", true)) {
                    List<String> gradle = JavaTools.gradle(ide, root);
                    gradle.add("-q");
                    gradle.add("classes");
                    MavenBuild.run(ide, gradle, root, "Building with Gradle");
                }
                classpath = gradleClasspath(module);
            } else {
                classpath = plainClasspath(root);
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(JavaTools.javaExecutable(ide));
            if (mode == ExecutionMode.DEBUG) {
                /* suspend=y: the VM waits for the debugger, so a breakpoint on the first
                   line of main is honoured instead of being installed after it ran. */
                cmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:"
                        + get("debugPort", "5005"));
            }
            cmd.addAll(Forms.splitArgs(get("vmArgs", "")));
            cmd.add("-cp");
            cmd.add(classpath);
            cmd.add(mainClass);
            cmd.addAll(Forms.splitArgs(get("args", "")));
            String wd = get("workingDir", "");
            Path cwd = wd.isBlank() ? module : Path.of(wd);
            return new ProcessSpec(name(), cmd, cwd, Forms.environment(get("env", "")));
        }

        private String gradleClasspath(Path module) {
            // Gradle keeps no classpath file we can read; the compiled classes plus every jar
            // in the Gradle cache the build script mentions is out of reach without the tooling
            // API, so Gradle applications are best run through the "run" task.
            List<String> parts = new ArrayList<>();
            for (String dir : List.of("build/classes/java/main", "build/classes/kotlin/main", "build/resources/main")) {
                Path p = module.resolve(dir);
                if (Files.isDirectory(p)) {
                    parts.add(p.toString());
                }
            }
            if (parts.isEmpty()) {
                throw new IllegalStateException("No compiled classes under " + module
                        + ". For Gradle projects prefer a Gradle Task configuration running 'run'.");
            }
            return String.join(java.io.File.pathSeparator, parts);
        }

        private String plainClasspath(Path root) {
            for (String dir : List.of("out", "bin", "build/classes", "target/classes")) {
                if (Files.isDirectory(root.resolve(dir))) {
                    return root.resolve(dir).toString();
                }
            }
            throw new IllegalStateException("No build tool and no compiled classes found under " + root);
        }
    }
}
