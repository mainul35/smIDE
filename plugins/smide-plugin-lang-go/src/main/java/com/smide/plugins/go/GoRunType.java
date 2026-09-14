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
