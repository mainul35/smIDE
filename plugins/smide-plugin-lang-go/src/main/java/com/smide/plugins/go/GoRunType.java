package com.smide.plugins.go;

import com.smide.api.Ide;
import com.smide.api.execution.BaseRunConfiguration;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.Forms;
import com.smide.api.execution.ProcessSpec;
import com.smide.api.execution.RunConfiguration;
import com.smide.api.execution.RunConfigurationType;
import com.smide.api.workspace.Workspace;
import javafx.scene.Node;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Go programs and tests: {@code go run}, {@code go test} and {@code go build} on a package,
 * a directory or a file.
 *
 * <p>Detected as well as made by hand. Every directory holding {@code package main} is a
 * program someone may want to run, so each is offered in the run list without anyone writing
 * a configuration, and a project with tests gets {@code go test ./...}.
 */
public final class GoRunType implements RunConfigurationType {

    public static final String ID = "go.run";
    /** Files looked at when finding programs, so a huge tree does not stall the run list. */
    private static final int MAX_FILES = 5000;
    private static final Set<String> SKIPPED = Set.of("vendor", "node_modules", "testdata");

    private final Ide ide;
    private final Function<Ide, Optional<Path>> go;

    /** @param go finds the go command, or nothing when the toolchain is not installed */
    public GoRunType(Ide ide, Function<Ide, Optional<Path>> go) {
        this.ide = ide;
        this.go = go;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Go";
    }

    @Override
    public String iconLiteral() {
        return "mdi2l-language-go";
    }

    @Override
    public boolean supportsDebug() {
        return true;
    }

    @Override
    public RunConfiguration create(Workspace workspace) {
        Config c = new Config(workspace);
        c.setName("Go");
        c.set("kind", "run");
        c.set("target", ".");
        return c;
    }

    @Override
    public List<RunConfiguration> detect(Workspace workspace) {
        Scan scan = scan(workspace.root());
        List<RunConfiguration> out = new ArrayList<>();
        for (Path dir : scan.mains()) {
            String target = relative(workspace.root(), dir);
            Config c = new Config(workspace);
            c.setName("go run " + target);
            c.set("kind", "run");
            c.set("target", target);
            out.add(c.temporary());
        }
        if (scan.hasTests()) {
            Config c = new Config(workspace);
            c.setName("go test ./...");
            c.set("kind", "test");
            c.set("target", "./...");
            out.add(c.temporary());
        }
        return out;
    }

    private static final java.util.regex.Pattern MAIN_FUNC =
            java.util.regex.Pattern.compile("^func\\s+main\\s*\\(\\s*\\)", java.util.regex.Pattern.MULTILINE);

    /**
     * The main function of a main package.
     *
     * <p>Run as its package, the way detection names it - unless another file in the same
     * folder has a main of its own. A folder of tutorial programs is exactly that, and as a
     * package it does not build ("main redeclared in this block"), so there each file runs
     * on its own.
     */
    @Override
    public List<com.smide.api.execution.RunMarker> markers(Workspace workspace, Path file, String text) {
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(".go") || fileName.endsWith("_test.go") || !file.startsWith(workspace.root())) {
            return List.of();
        }
        java.util.regex.Matcher main = MAIN_FUNC.matcher(text);
        if (!main.find() || !declaresMain(text)) {
            return List.of();
        }
        String target = otherMainBeside(file)
                ? relative(workspace.root(), file)
                : relative(workspace.root(), file.getParent());
        String name = "go run " + target;
        return List.of(new com.smide.api.execution.RunMarker(
                com.smide.api.execution.RunMarker.lineOf(text, main.start()), name, () -> {
                    Config c = new Config(workspace);
                    c.setName(name);
                    c.set("kind", "run");
                    c.set("target", target);
                    return c.temporary();
                }));
    }

    /** Whether another Go file in the same folder declares a main function in package main. */
    private static boolean otherMainBeside(Path file) {
        try (Stream<Path> siblings = Files.list(file.getParent())) {
            return siblings.filter(p -> !p.equals(file))
                    .filter(p -> p.getFileName().toString().endsWith(".go") && !p.getFileName().toString().endsWith("_test.go"))
                    .anyMatch(p -> {
                        try {
                            String text = Files.readString(p);
                            return MAIN_FUNC.matcher(text).find() && declaresMain(text);
                        } catch (IOException | RuntimeException e) {
                            return false;
                        }
                    });
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether a Go source's package clause - its first line that is not a comment - says main. */
    static boolean declaresMain(String text) {
        boolean inBlock = false;
        for (String raw : (Iterable<String>) text.lines().limit(80)::iterator) {
            String line = raw.strip();
            if (inBlock) {
                int end = line.indexOf("*/");
                if (end < 0) {
                    continue;
                }
                inBlock = false;
                line = line.substring(end + 2).strip();
            }
            if (line.startsWith("/*")) {
                inBlock = !line.contains("*/");
                continue;
            }
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            if (line.startsWith("package ")) {
                String name = line.substring("package ".length()).strip();
                int comment = name.indexOf("//");
                return (comment >= 0 ? name.substring(0, comment).strip() : name).equals("main");
            }
            return false;
        }
        return false;
    }

    @Override
    public Node editor(RunConfiguration configuration) {
        Config c = (Config) configuration;
        GridPane grid = Forms.grid();
        Forms.combo(grid, 0, "Kind", c, "kind", List.of("run", "test", "build"));
        Forms.text(grid, 1, "Package or file", c, "target", "., ./cmd/app, ./... or main.go");
        Forms.text(grid, 2, "Program arguments", c, "args", "passed to the program by go run");
        Forms.text(grid, 3, "Test filter (-run)", c, "testFilter", "TestName");
        Forms.text(grid, 4, "Build flags", c, "flags", "-race");
        Forms.text(grid, 5, "Environment", c, "env", "KEY=value;OTHER=value");
        Forms.directory(grid, 6, "Working directory", c, "workingDir", ide);
        return new VBox(8, grid, Forms.note("Runs in the module the target belongs to - the nearest folder"
                + " above it holding a go.mod - so imports resolve as go build sees them. Without a go.mod,"
                + " the .go files of the target's folder are run together."));
    }

    private final class Config extends BaseRunConfiguration {
        Config(Workspace workspace) {
            super(GoRunType.this, workspace);
        }

        @Override
        public ProcessSpec prepare(Ide ide, ExecutionMode mode) throws Exception {
            Path goBinary = go.apply(ide).orElseThrow(() -> new IllegalStateException(
                    "The Go toolchain was not found. Install it from " + GoToolchain.DOWNLOAD
                            + ", or set its folder in Settings > Languages > Go."));
            String kind = get("kind", "run");
            Launch launch = launch(workspace.root(), get("target", "."), kind);
            if (mode == ExecutionMode.DEBUG) {
                return debug(goBinary, kind, launch);
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(goBinary.toString());
            cmd.add(kind.equals("test") || kind.equals("build") ? kind : "run");
            cmd.addAll(Forms.splitArgs(get("flags", "")));
            String filter = get("testFilter", "");
            if (kind.equals("test") && !filter.isBlank()) {
                cmd.add("-run");
                cmd.add(filter);
            }
            cmd.addAll(launch.targets());
            if (!kind.equals("test") && !kind.equals("build")) {
                cmd.addAll(Forms.splitArgs(get("args", "")));
            }
            String wd = get("workingDir", "");
            Path cwd = wd.isBlank() ? launch.directory() : Path.of(wd);
            return new ProcessSpec(name(), cmd, cwd, Forms.environment(get("env", "")));
        }

        /**
         * The program or tests under Delve, headless: the output stays in the Run window, and
         * the IDE attaches to Delve over the Debug Adapter Protocol.
         */
        private ProcessSpec debug(Path goBinary, String kind, Launch launch) throws Exception {
            if (kind.equals("build")) {
                throw new com.smide.api.execution.CannotRunException(
                        "A go build has nothing to debug. Choose run or test as the kind.");
            }
            // A Delve that debugs this toolchain's Go: each release refuses versions outside its window.
            Delve.Choice delve = Delve.forGo(ide, goBinary);
            if (delve.binary() == null) {
                throw new com.smide.api.execution.CannotRunException(delve.problem(),
                        new com.smide.api.ui.Notifications.NotificationAction(delve.installLabel(),
                                () -> Delve.install(ide, goBinary)));
            }
            Path dlv = delve.binary();
            List<String> cmd = new ArrayList<>();
            cmd.add(dlv.toString());
            if (kind.equals("test")) {
                if (launch.targets().size() != 1 || launch.targets().get(0).endsWith("...")) {
                    throw new com.smide.api.execution.CannotRunException("Delve debugs the tests of one package at a"
                            + " time. Set the package, such as ./internal/store, instead of " + get("target", ".") + ".");
                }
                cmd.add("test");
            } else {
                cmd.add("debug");
            }
            cmd.addAll(launch.targets());
            // Delve writes the binary it debugs beside the sources unless told otherwise, leaving
            // a __debug_bin in the project after every session.
            Path binary = java.nio.file.Files.createTempDirectory("smide-dlv")
                    .resolve(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                            ? "__debug_bin.exe" : "__debug_bin");
            binary.getParent().toFile().deleteOnExit();
            cmd.add("--output=" + binary);
            int port = com.smide.api.debug.DebugAdapters.freePort();
            set("debugPort", String.valueOf(port));
            cmd.addAll(List.of("--headless", "--listen=127.0.0.1:" + port, "--api-version=2"));
            String flags = get("flags", "").strip();
            if (!flags.isEmpty()) {
                cmd.add("--build-flags=" + flags);
            }
            List<String> programArgs = new ArrayList<>();
            if (kind.equals("test")) {
                String filter = get("testFilter", "");
                if (!filter.isBlank()) {
                    programArgs.addAll(List.of("-test.run", filter));
                }
            } else {
                programArgs.addAll(Forms.splitArgs(get("args", "")));
            }
            if (!programArgs.isEmpty()) {
                cmd.add("--");
                cmd.addAll(programArgs);
            }
            String wd = get("workingDir", "");
            Path cwd = wd.isBlank() ? launch.directory() : Path.of(wd);
            // Delve runs go build itself, so the toolchain found here goes on its PATH.
            java.util.Map<String, String> env = new java.util.HashMap<>();
            env.put("PATH", goBinary.getParent() + java.io.File.pathSeparator
                    + java.util.Objects.requireNonNullElse(System.getenv("PATH"), ""));
            env.putAll(Forms.environment(get("env", "")));
            return new ProcessSpec(name(), cmd, cwd, env);
        }
    }

    // ------------------------------------------------------------- launching

    /** Where to run the go command, and what to hand it. */
    record Launch(Path directory, List<String> targets) {
    }

    /**
     * What a target written relative to the workspace means to the go command.
     *
     * <p>Inside a module, the command runs at the module's root with the target made relative
     * to it, which is the only way imports of the module's own packages resolve. Outside one,
     * go cannot name a directory, so the directory's own files are named instead - how a
     * folder of tutorial programs with no go.mod gets run at all.
     */
    static Launch launch(Path root, String target, String kind) throws IOException {
        String written = target == null || target.isBlank() ? "." : target.strip();
        boolean recursive = written.endsWith("/...") || written.equals("...");
        String base = recursive ? written.substring(0, Math.max(0, written.length() - 4)) : written;
        Path path = root.resolve(base.isEmpty() ? "." : base).normalize();
        Path dir = Files.isRegularFile(path) ? path.getParent() : path;
        Path module = moduleOf(dir, root);
        if (module != null) {
            String relative = relative(module, path);
            return new Launch(module, List.of(recursive ? (relative.equals(".") ? "./..." : relative + "/...") : relative));
        }
        if (Files.isRegularFile(path)) {
            return new Launch(dir, List.of(path.getFileName().toString()));
        }
        if (kind.equals("run")) {
            List<String> files = new ArrayList<>();
            try (Stream<Path> list = Files.list(dir)) {
                list.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".go") && !n.endsWith("_test.go"))
                        .sorted()
                        .forEach(files::add);
            }
            if (files.isEmpty()) {
                throw new IllegalStateException("There are no Go files to run in " + dir + ".");
            }
            return new Launch(dir, files);
        }
        return new Launch(dir, List.of(recursive ? "./..." : "."));
    }

    /** The nearest folder from {@code dir} up to the workspace root that holds a go.mod. */
    static Path moduleOf(Path dir, Path root) {
        Path normalRoot = root.toAbsolutePath().normalize();
        for (Path current = dir.toAbsolutePath().normalize(); current != null && current.startsWith(normalRoot);
             current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("go.mod"))) {
                return current;
            }
        }
        return null;
    }

    /** {@code .} for the same folder, else {@code ./a/b} with forward slashes, as go writes it. */
    static String relative(Path from, Path to) {
        Path rel = from.toAbsolutePath().normalize().relativize(to.toAbsolutePath().normalize());
        String text = rel.toString().replace('\\', '/');
        return text.isEmpty() ? "." : "./" + text;
    }

    // --------------------------------------------------------------- finding

    /** The folders holding a main package, and whether any tests exist. */
    record Scan(List<Path> mains, boolean hasTests) {
    }

    /** Walks the project for Go programs and tests, skipping hidden, vendored and test-data folders. */
    static Scan scan(Path root) {
        Set<Path> mains = new TreeSet<>();
        boolean tests = false;
        int seen = 0;
        Deque<Path> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty() && seen < MAX_FILES) {
            Path dir = pending.poll();
            try (Stream<Path> children = Files.list(dir)) {
                for (Path child : children.sorted().toList()) {
                    String name = child.getFileName().toString();
                    if (Files.isDirectory(child)) {
                        if (!name.startsWith(".") && !SKIPPED.contains(name)) {
                            pending.add(child);
                        }
                    } else if (name.endsWith(".go") && ++seen <= MAX_FILES) {
                        if (name.endsWith("_test.go")) {
                            tests = true;
                        } else if (!mains.contains(dir) && isMain(child)) {
                            mains.add(dir);
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                // An unreadable folder has nothing to offer.
            }
        }
        return new Scan(new ArrayList<>(mains), tests);
    }

    /** Whether a Go file's package clause says {@code main}, reading past comments and build tags. */
    static boolean isMain(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            boolean inBlock = false;
            for (String raw : (Iterable<String>) lines.limit(80)::iterator) {
                String line = raw.strip();
                if (inBlock) {
                    int end = line.indexOf("*/");
                    if (end < 0) {
                        continue;
                    }
                    inBlock = false;
                    line = line.substring(end + 2).strip();
                }
                if (line.startsWith("/*")) {
                    inBlock = !line.contains("*/");
                    continue;
                }
                if (line.isEmpty() || line.startsWith("//")) {
                    continue;
                }
                if (line.startsWith("package ")) {
                    String name = line.substring("package ".length()).strip();
                    int comment = name.indexOf("//");
                    return (comment >= 0 ? name.substring(0, comment).strip() : name).equals("main");
                }
                return false;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return false;
    }
}
