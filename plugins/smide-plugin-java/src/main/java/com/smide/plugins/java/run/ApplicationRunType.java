package com.smide.plugins.java.run;

import com.smide.api.Ide;
import com.smide.api.execution.BaseRunConfiguration;
import com.smide.api.execution.Forms;
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
            out.add(configFor(workspace, info.get(), rc));
        }
        return out;
    }

    private RunConfiguration configFor(Workspace workspace, JavaProjectInfo info, JavaProjectInfo.RunnableClass rc) {
        Config c = new Config(workspace);
        c.setName(rc.simpleName());
        c.set("mainClass", rc.fqn());
        /* The class path is the assembly's when there is one. A main class in a
           module that the application is assembled from - smIDE's own Launcher, in
           a reactor whose plugins it does not depend on - runs with its module's
           class path and comes up missing everything the assembly adds. */
        Path classpathModule = AssemblyModule.of(info.model(), rc.moduleRoot())
                .orElse(rc.moduleRoot());
        c.set("module", Forms.relative(workspace.root(), classpathModule));
        c.temporary();
        return c;
    }

    /**
     * What the project can answer about a configuration somebody is filling in by hand.
     *
     * <p>A configuration made with Add starts empty, and the fields that matter are not a
     * matter of taste: the main class is one of the project's, and the module whose class path
     * it runs with follows from where that class lives and which module assembles it. Only
     * blanks are filled; what is already there was somebody's decision.
     */
    @Override
    public List<String> complete(Workspace workspace, RunConfiguration configuration) {
        if (!(configuration instanceof Config c)) {
            return List.of();
        }
        JavaProjectInfo info = registry.get(workspace).orElse(null);
        if (info == null) {
            return List.of();
        }
        List<String> filled = new ArrayList<>();
        String mainClass = c.get("mainClass", "");
        if (mainClass.isBlank() && !info.mainClasses().isEmpty()) {
            mainClass = info.mainClasses().get(0).fqn();
            c.set("mainClass", mainClass);
            filled.add("Main class");
        }
        if (c.get("module", "").isBlank()) {
            Path module = classpathModule(info, mainClass);
            if (module != null) {
                c.set("module", Forms.relative(workspace.root(), module));
                filled.add("Classpath of module");
            }
        }
        if (c.get("workingDir", "").isBlank() && !c.get("module", "").isBlank()) {
            c.set("workingDir", workspace.root().resolve(c.get("module", "")).toString());
            filled.add("Working directory");
        }
        return filled;
    }

    /**
     * The module whose class path a main class runs with: the one that assembles the
     * application when there is one, otherwise the module the class lives in.
     */
    private static Path classpathModule(JavaProjectInfo info, String mainClass) {
        for (JavaProjectInfo.RunnableClass rc : info.mainClasses()) {
            if (rc.fqn().equals(mainClass)) {
                return AssemblyModule.of(info.model(), rc.moduleRoot()).orElse(rc.moduleRoot());
            }
        }
        return null;
    }

    private static final java.util.regex.Pattern MAIN_METHOD = java.util.regex.Pattern.compile(
            "^[ \\t]*(?:public\\s+)?(?:static\\s+)?(?:final\\s+)?void\\s+main\\s*\\(", java.util.regex.Pattern.MULTILINE);
    private static final java.util.regex.Pattern PACKAGE =
            java.util.regex.Pattern.compile("^[ \\t]*package\\s+([\\w.]+)\\s*;", java.util.regex.Pattern.MULTILINE);

    /**
     * The main method of a class. Run as the detected configuration of the same class when
     * the import knows it, so the class path is the one detection works out; a class the
     * import has not seen yet - written since - runs from the module the file is in.
     */
    @Override
    public List<com.smide.api.execution.RunMarker> markers(Workspace workspace, Path file, String text) {
        if (!file.getFileName().toString().endsWith(".java") || !file.startsWith(workspace.root())) {
            return List.of();
        }
        java.util.regex.Matcher main = MAIN_METHOD.matcher(text);
        if (!main.find()) {
            return List.of();
        }
        int line = com.smide.api.execution.RunMarker.lineOf(text, main.start());
        Optional<JavaProjectInfo> info = registry.get(workspace);
        if (info.isPresent()) {
            for (JavaProjectInfo.RunnableClass rc : info.get().mainClasses()) {
                if (rc.file() != null && rc.file().toAbsolutePath().normalize().equals(file)) {
                    return List.of(new com.smide.api.execution.RunMarker(line, rc.simpleName(),
                            () -> configFor(workspace, info.get(), rc)));
                }
            }
        }
        String simple = file.getFileName().toString().replaceFirst("\\.java$", "");
        java.util.regex.Matcher pkg = PACKAGE.matcher(text);
        String fqn = pkg.find() ? pkg.group(1) + "." + simple : simple;
        return List.of(new com.smide.api.execution.RunMarker(line, simple, () -> {
            Config c = new Config(workspace);
            c.setName(simple);
            c.set("mainClass", fqn);
            c.set("module", Forms.relative(workspace.root(), moduleOf(workspace.root(), file)));
            c.temporary();
            return c;
        }));
    }

    /** The nearest folder above a source with a build file, or the workspace root. */
    private static Path moduleOf(Path root, Path file) {
        for (Path dir = file.getParent(); dir != null && dir.startsWith(root); dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("pom.xml")) || Files.isRegularFile(dir.resolve("build.gradle"))
                    || Files.isRegularFile(dir.resolve("build.gradle.kts"))) {
                return dir;
            }
        }
        return root;
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
        Forms.text(grid, 3, "Classpath of module", c, "module", "worked out from the main class");
        Forms.directory(grid, 4, "Working directory", c, "workingDir", ide);
        Forms.text(grid, 5, "Environment (K=V;K=V)", c, "env", "");
        Forms.text(grid, 6, "Debug port", c, "debugPort", "5005");
        Forms.check(grid, 7, "Build before run", c, "build", true);
        return new VBox(8, grid, Forms.note("Debug starts the program with a JDWP agent on the port above and waits for the IDE to attach, so a breakpoint on the first line is honoured."),
                Forms.note("Classpath of module is the module whose dependencies the program runs with,"
                        + " relative to the workspace root. It defaults to the module the main class is in,"
                        + " which is right until the program needs something that module does not depend on"
                        + " - an application assembled by an aggregator module, for instance, which is how"
                        + " smIDE itself is put together."));
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
            JavaProjectInfo info = registry.get(workspace).orElse(null);
            Path module = moduleFor(info, mainClass);
            // Built, run and debugged with the project's JDK, not whichever the IDE found first.
            Path jdk = JavaTools.launchJdk(ide, workspace, registry).home();
            String classpath;
            if (info != null && info.isMaven()) {
                classpath = MavenBuild.compileAndClasspath(ide, jdk, root, module, !flag("build", true));
            } else if (info != null && info.isGradle()) {
                if (flag("build", true)) {
                    List<String> gradle = JavaTools.gradle(ide, info.buildRoot());
                    gradle.add("-q");
                    gradle.add("classes");
                    MavenBuild.run(ide, jdk, gradle, info.buildRoot(), "Building with Gradle");
                }
                classpath = gradleClasspath(module);
            } else {
                classpath = plainClasspath(root);
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(JavaTools.javaExecutable(jdk));
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
            // JAVA_HOME for anything the program starts itself; the configuration's own variables win.
            java.util.Map<String, String> env = new java.util.HashMap<>(JavaTools.environment(jdk));
            env.putAll(Forms.environment(get("env", "")));
            return new ProcessSpec(name(), cmd, cwd, env);
        }

        /**
         * The module to run with: the one named in the configuration, or - when that field was
         * left blank - the one the main class belongs to.
         *
         * <p>Blank used to mean the workspace root, which in a multi-module build is a pom with
         * no code of its own: the class path came out as {@code <root>/target/classes}, a folder
         * that does not exist, and the run failed with "Could not find or load main class". A
         * field nobody filled in is a question the project can answer.
         */
        private Path moduleFor(JavaProjectInfo info, String mainClass) {
            if (!get("module", "").isBlank() || info == null) {
                return moduleDir();
            }
            Path found = classpathModule(info, mainClass);
            return found == null ? moduleDir() : found;
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
